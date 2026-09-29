package dev.warin.aicomments

/** Half-open `[start, end)` offset range into the document text. */
data class Span(val start: Int, val end: Int)

enum class CommentKind { LINE, BLOCK }

/**
 * A comment as seen by the parser, independent of language and PSI.
 *
 * @param span the comment token itself, used for highlighting.
 * @param bodyEnd offset right after the comment body, i.e. before a block comment suffix.
 * @param body the text between the comment delimiters.
 * @param removal what to delete when stripping; wider than [span] when the comment has a wrapper
 *   that is meaningless without it, like the braces of a comment-only JSX expression.
 */
data class CommentToken(
    val kind: CommentKind,
    val span: Span,
    val bodyEnd: Int,
    val body: String,
    val removal: Span = span,
)

data class AiComment(val parts: List<Span>, val removal: Span)

/** An opener without a closer; [closeAt] is where the missing ` —` would most likely belong. */
data class UnterminatedAiComment(val span: Span, val closeAt: Int)

data class AiCommentParseResult(
    val comments: List<AiComment>,
    val unterminated: List<UnterminatedAiComment>,
)

/**
 * AI comments are delimited by em-dashes: the trimmed body starts with `—` followed by whitespace
 * and ends with whitespace followed by `—`. A run of adjacent line comments forms one AI comment
 * from the line that opens to the line that closes.
 */
object AiCommentParser {
    const val DASH = '—'

    fun parse(text: CharSequence, tokens: List<CommentToken>): AiCommentParseResult {
        val comments = mutableListOf<AiComment>()
        val unterminated = mutableListOf<UnterminatedAiComment>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val body = normalizedBody(token)
            when {
                isComplete(body) -> comments += AiComment(listOf(token.span), token.removal)
                !opens(body) -> Unit
                token.kind == CommentKind.BLOCK -> unterminated += UnterminatedAiComment(token.span, token.bodyEnd)
                else -> {
                    val closer = findCloser(text, tokens, i)
                    if (closer.closedAt >= 0) {
                        val group = tokens.subList(i, closer.closedAt + 1)
                        comments += AiComment(group.map { it.span }, Span(token.removal.start, group.last().removal.end))
                        i = closer.closedAt + 1
                        continue
                    }
                    unterminated += UnterminatedAiComment(token.span, tokens[closer.lastInGroup].bodyEnd)
                }
            }
            i++
        }
        return AiCommentParseResult(comments, unterminated)
    }

    private class CloserSearch(val closedAt: Int, val lastInGroup: Int)

    private fun findCloser(text: CharSequence, tokens: List<CommentToken>, openerIndex: Int): CloserSearch {
        var j = openerIndex + 1
        while (j < tokens.size && isNextLine(text, tokens[j - 1], tokens[j])) {
            if (closes(normalizedBody(tokens[j]))) return CloserSearch(j, j)
            j++
        }
        return CloserSearch(-1, j - 1)
    }

    private fun isNextLine(text: CharSequence, previous: CommentToken, next: CommentToken): Boolean {
        if (previous.kind != CommentKind.LINE || next.kind != CommentKind.LINE) return false
        var newlines = 0
        for (offset in previous.span.end until next.span.start) {
            val char = text[offset]
            if (char == '\n') newlines++ else if (!char.isWhitespace()) return false
        }
        return newlines == 1
    }

    /** Block comments may decorate continuation lines with a leading `*`, which is not part of the body. */
    private fun normalizedBody(token: CommentToken): String {
        if (token.kind == CommentKind.LINE) return token.body.trim()
        return token.body.lines().joinToString("\n") { it.trim().removePrefix("*").trim() }.trim()
    }

    private fun opens(body: String) = body.firstOrNull() == DASH && (body.length == 1 || body[1].isWhitespace())

    private fun closes(body: String) = body.lastOrNull() == DASH && (body.length == 1 || body[body.length - 2].isWhitespace())

    private fun isComplete(body: String) = body.length >= 3 && opens(body) && closes(body) && body.substring(1, body.length - 1).isNotBlank()
}
