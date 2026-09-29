package dev.warin.aicomments.cli

import dev.warin.aicomments.AiCommentStripper
import dev.warin.aicomments.CommentLanguages
import dev.warin.aicomments.parseAiComments
import kotlin.system.exitProcess

private const val USAGE = """ai-comments $VERSION

Usage:
  ai-comments check <file>...        report unterminated AI comments and AI comments in unsupported file types
  ai-comments strip <file>...        remove AI comments from files in place
  ai-comments extensions             list the file types AI comments are supported in
  ai-comments rule <template>        print the Claude rule with the supported file types filled in
  ai-comments hook pre-tool-use      Claude Code PreToolUse hook: strip before `git commit` (reads JSON on stdin)
  ai-comments hook post-tool-use     Claude Code PostToolUse hook: check edited files (reads JSON on stdin)
"""

fun main(args: Array<String>) {
    val status = try {
        run(args.toList())
    } catch (error: Throwable) {
        // Never exit with 2: for Claude Code hooks that would block the tool call, and a bug here must not block commits.
        PosixHost.printError("ai-comments: ${error.message ?: error}")
        1
    }
    exitProcess(status)
}

private fun run(args: List<String>): Int {
    when (args.firstOrNull()) {
        "check" -> {
            val problems = args.drop(1).flatMap { path ->
                val text = PosixHost.readBytes(path)?.decodeToString() ?: return@flatMap listOf("$path: cannot read")
                Checks.check(path, text)
            }
            problems.forEach(::println)
            return if (problems.isEmpty()) 0 else 1
        }
        "strip" -> {
            for (path in args.drop(1)) {
                val text = PosixHost.readBytes(path)?.decodeToString() ?: continue
                val comments = parseAiComments(path, text)?.comments.orEmpty()
                if (comments.isEmpty()) continue
                PosixHost.writeBytes(path, AiCommentStripper.strip(text, comments).encodeToByteArray())
                println("$path: ${comments.size}")
            }
            return 0
        }
        "extensions" -> println(CommentLanguages.describeSupported())
        "rule" -> {
            val template = args.getOrNull(1)?.let { PosixHost.readBytes(it) } ?: return usage()
            print(Hooks.rule(template.decodeToString()))
        }
        "hook" -> {
            val output = when (args.getOrNull(1)) {
                "pre-tool-use" -> Hooks.preToolUse(PosixHost.readStdin(), PosixHost)
                "post-tool-use" -> Hooks.postToolUse(PosixHost.readStdin(), PosixHost)
                else -> return usage()
            }
            output?.let(::println)
        }
        "--version", "version" -> println(VERSION)
        else -> return usage()
    }
    return 0
}

private fun usage(): Int {
    print(USAGE)
    return 1
}
