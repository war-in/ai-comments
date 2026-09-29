@file:OptIn(ExperimentalJsExport::class)

package dev.warin.aicomments.cli

import dev.warin.aicomments.AiCommentStripper
import dev.warin.aicomments.CommentLanguages
import dev.warin.aicomments.parseAiComments

/** The JS-facing API of the VS Code extension. Plain arrays and numbers only, so the TypeScript side stays simple. */

@JsExport
class JsRange(val start: Int, val end: Int)

@JsExport
class JsProblem(val start: Int, val end: Int, val message: String, val closeAt: Int?)

@JsExport
class JsAnalysis(
    /** Ranges to highlight: each part of every complete AI comment. */
    val highlights: Array<JsRange>,
    val problems: Array<JsProblem>,
    val supported: Boolean,
)

@JsExport
fun analyze(path: String, text: String): JsAnalysis {
    val result = parseAiComments(path, text)
    val highlights = result?.comments.orEmpty().flatMap { it.parts }.map { JsRange(it.start, it.end) }
    val problems = Checks.problems(path, text).map { JsProblem(it.start, it.end, it.message, it.closeAt) }
    return JsAnalysis(highlights.toTypedArray(), problems.toTypedArray(), result != null)
}

/** Deletions to apply, sorted by descending offset. Empty when there is nothing to strip or the type is unsupported. */
@JsExport
fun deletions(path: String, text: String): Array<JsRange> {
    val comments = parseAiComments(path, text)?.comments.orEmpty()
    return AiCommentStripper.deletions(text, comments).map { JsRange(it.start, it.end) }.toTypedArray()
}

/**
 * Strips what a commit in [dir] will include: the index, plus the working tree of every changed file when
 * [includeWorkingTree], which is what VS Code's smart commit stages when nothing is staged.
 * Returns a summary for the user, or an empty string when nothing needed stripping.
 */
@JsExport
fun stripForCommit(dir: String, includeWorkingTree: Boolean): String {
    val scopes = buildList {
        add(StripScope.Staged(dir))
        if (includeWorkingTree) add(StripScope.WorkingTree(dir, listOf(":/"), includeUntracked = true))
    }
    val report = CommitStripper(NodeHost).run(CommitPlan.Exact(scopes), dir)
    return buildString {
        if (report.stripped.isNotEmpty()) append("Stripped AI comments from ${report.stripped.keys.joinToString()}.")
        if (report.unterminated.isNotEmpty()) {
            if (isNotEmpty()) append(' ')
            append("Unterminated AI comments are committed as is: ${report.unterminated.joinToString()}.")
        }
    }
}

@JsExport
fun supportedFileTypes(): String = CommentLanguages.describeSupported()

@JsExport
fun version(): String = VERSION
