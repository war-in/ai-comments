package dev.warin.aicomments.cli

import dev.warin.aicomments.AiCommentParseResult
import dev.warin.aicomments.AiCommentStripper
import dev.warin.aicomments.parseAiComments

class StripReport {
    /** Repository-relative path to the number of AI comments removed from it. */
    val stripped = linkedMapOf<String, Int>()

    /** `path:line` of openers that were left in place because they never close. */
    val unterminated = linkedSetOf<String>()

    val isEmpty get() = stripped.isEmpty() && unterminated.isEmpty()
}

/**
 * Strips AI comments from everything a commit is about to include. The index and the working tree are
 * stripped separately, because a partially staged file has different content in each.
 */
class CommitStripper(private val host: Host) {
    fun run(plan: CommitPlan, cwd: String): StripReport {
        val report = StripReport()
        val scopes = when (plan) {
            CommitPlan.None -> return report
            is CommitPlan.Unknown -> listOf(StripScope.Staged(plan.dir), StripScope.WorkingTree(plan.dir, listOf(":/"), includeUntracked = true))
            is CommitPlan.Exact -> plan.scopes
        }
        for (scope in scopes) {
            val dir = host.expandHome(scope.dir.ifEmpty { cwd })
            val top = host.git(dir, "rev-parse", "--show-toplevel").takeIf { it.exitCode == 0 }?.text?.trim() ?: continue
            when (scope) {
                is StripScope.Staged -> {
                    val staged = host.git(top, "diff", "--cached", "--name-only", "-z", "--diff-filter=ACMR")
                    for (path in nulSeparated(staged)) {
                        stripIndex(top, path, report)
                        stripWorkingTree(top, path, report)
                    }
                }
                is StripScope.WorkingTree -> {
                    if (scope.pathspecs.isEmpty()) continue
                    val flags = if (scope.includeUntracked) arrayOf("-m", "-o", "--exclude-standard") else arrayOf("-m")
                    val files = host.git(dir, "ls-files", "-z", "--full-name", *flags, "--", *scope.pathspecs.toTypedArray())
                    nulSeparated(files).distinct().forEach { stripWorkingTree(top, it, report) }
                }
            }
        }
        return report
    }

    private fun stripIndex(top: String, path: String, report: StripReport) {
        val entry = host.git(top, "ls-files", "-s", "-z", "--", path).text.trimEnd('\u0000')
        val (mode, sha, stage) = entry.substringBefore('\t').split(' ').takeIf { it.size == 3 } ?: return
        if (stage != "0" || (mode != "100644" && mode != "100755")) return
        val text = decode(host.git(top, "cat-file", "blob", sha).stdout) ?: return
        val (result, stripped) = strip(path, text, report) ?: return
        val temp = host.writeTemp(stripped.encodeToByteArray())
        try {
            val newSha = host.git(top, "hash-object", "-w", "--no-filters", "--", temp).text.trim()
            if (newSha.isEmpty()) return
            host.git(top, "update-index", "--cacheinfo", "$mode,$newSha,$path")
            record(report, path, result)
        } finally {
            host.delete(temp)
        }
    }

    private fun stripWorkingTree(top: String, path: String, report: StripReport) {
        val file = "$top/$path"
        val text = host.readBytes(file)?.let(::decode) ?: return
        val (result, stripped) = strip(path, text, report) ?: return
        host.writeBytes(file, stripped.encodeToByteArray())
        record(report, path, result)
    }

    private fun strip(path: String, text: String, report: StripReport): Pair<AiCommentParseResult, String>? {
        val result = parseAiComments(path, text) ?: return null
        result.unterminated.forEach { report.unterminated += "$path:${lineOf(text, it.span.start)}" }
        if (result.comments.isEmpty()) return null
        return result to AiCommentStripper.strip(text, result.comments)
    }

    private fun record(report: StripReport, path: String, result: AiCommentParseResult) {
        report.stripped[path] = maxOf(report.stripped[path] ?: 0, result.comments.size)
    }

    /** Only valid UTF-8 text is touched, so binary or differently encoded files can't be corrupted by a round trip. */
    private fun decode(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun nulSeparated(result: ProcessResult) =
        if (result.exitCode != 0) emptyList() else result.text.split('\u0000').filter { it.isNotEmpty() }
}

fun lineOf(text: CharSequence, offset: Int): Int {
    var line = 1
    for (i in 0 until offset) if (text[i] == '\n') line++
    return line
}
