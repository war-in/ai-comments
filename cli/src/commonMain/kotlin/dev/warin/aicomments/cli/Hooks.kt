package dev.warin.aicomments.cli

import dev.warin.aicomments.CommentLanguages
import dev.warin.aicomments.parseAiComments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * A problem in one file. [start] and [end] are offsets; [closeAt] is where a missing closing dash belongs,
 * for unterminated AI comments only.
 */
data class Problem(val line: Int, val start: Int, val end: Int, val message: String, val closeAt: Int? = null)

object Checks {
    /** Opening dash right after any common comment marker; used only for file types the lexer can't read. */
    private val suspect = Regex("""(^|\s|\{)(//|#|--|/\*|<!--|;|%)\s*— \S""")

    const val UNTERMINATED = "Unterminated AI comment. Close it with ` —`, otherwise it is not stripped and gets committed."
    const val UNSUPPORTED = "AI comments are not supported in this file type, so nothing would strip it before commit. " +
        "Rewrite it as a regular comment, or delete it."

    fun problems(path: String, text: String): List<Problem> {
        val result = parseAiComments(path, text)
        if (result != null) {
            return result.unterminated.map { Problem(lineOf(text, it.span.start), it.span.start, it.span.end, UNTERMINATED, it.closeAt) }
        }
        val problems = mutableListOf<Problem>()
        var lineStart = 0
        text.split('\n').forEachIndexed { index, line ->
            suspect.find(line)?.let { problems += Problem(index + 1, lineStart + it.range.first, lineStart + line.length, UNSUPPORTED) }
            lineStart += line.length + 1
        }
        return problems
    }

    fun check(path: String, text: String): List<String> = problems(path, text).map { "$path:${it.line}: ${it.message}" }
}

/** Claude Code hook handlers: each reads the hook input JSON and returns the output JSON, or null for no output. */
object Hooks {
    private val json = Json { ignoreUnknownKeys = true }

    fun preToolUse(input: String, host: Host): String? {
        val event = json.parseToJsonElement(input).jsonObject
        if (event.string("tool_name") != "Bash") return null
        val command = event.obj("tool_input")?.string("command") ?: return null
        val cwd = event.string("cwd") ?: return null
        val plan = ShellCommand.plan(command, cwd)
        if (plan == CommitPlan.None) return null

        val report = CommitStripper(host).run(plan, cwd)
        if (report.isEmpty) return null
        val message = buildString {
            if (report.stripped.isNotEmpty()) {
                append("ai-comments stripped AI comments before this commit from: ")
                append(report.stripped.entries.joinToString { "${it.key} (${it.value})" })
                append(". The working tree no longer contains them.")
            }
            if (report.unterminated.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append("Unterminated AI comments were left in place and will be committed as is: ")
                append(report.unterminated.joinToString())
                append('.')
            }
        }
        return buildJsonObject {
            putJsonObject("hookSpecificOutput") {
                put("hookEventName", "PreToolUse")
                put("additionalContext", message)
            }
        }.toString()
    }

    fun postToolUse(input: String, host: Host): String? {
        val event = json.parseToJsonElement(input).jsonObject
        if (event.string("tool_name") !in setOf("Edit", "Write", "MultiEdit")) return null
        val path = event.obj("tool_input")?.string("file_path") ?: return null
        val text = host.readBytes(path)?.decodeToString() ?: return null
        val problems = Checks.check(path, text)
        if (problems.isEmpty()) return null
        return buildJsonObject {
            put("decision", "block")
            put("reason", problems.joinToString("\n"))
        }.toString()
    }

    const val RULE_PLACEHOLDER = "{{SUPPORTED_FILE_TYPES}}"

    fun rule(template: String) = template.replace(RULE_PLACEHOLDER, CommentLanguages.describeSupported())

    private fun JsonObject.string(key: String) = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
}
