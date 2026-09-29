package dev.warin.aicomments

/**
 * Finds comments without a real parser, for places that have no PSI: the CLI, VS Code, and plain-text files in the IDE.
 *
 * It skips string literals so markers inside them are not comments. Where it guesses wrong (regex literals,
 * nested template literals, heredocs), it only ever misses comments, and an AI comment additionally needs its
 * dashes, so a wrong strip is practically impossible.
 */
object CommentLexer {
    fun tokenize(text: CharSequence, syntax: CommentSyntax): List<CommentToken> {
        val tokens = mutableListOf<CommentToken>()
        val lineComments = syntax.lineComments.sortedByDescending { it.length }
        val blockComments = syntax.blockComments.sortedByDescending { it.first.length }
        val multiLineStrings = syntax.multiLineStrings.sortedByDescending { it.length }
        var i = 0
        loop@ while (i < text.length) {
            for (delimiter in multiLineStrings) {
                if (text.startsWith(delimiter, i)) {
                    i = skipString(text, i + delimiter.length, delimiter, multiLine = true)
                    continue@loop
                }
            }
            if (text[i] in syntax.singleLineStrings) {
                i = skipString(text, i + 1, text[i].toString(), multiLine = false)
                continue@loop
            }
            for ((open, close) in blockComments) {
                if (!text.startsWith(open, i)) continue
                val closeAt = indexOf(text, close, i + open.length)
                if (closeAt < 0) break@loop
                val end = closeAt + close.length
                val isDoc = syntax.docCommentPrefix != null && open == "/*" &&
                    text.startsWith(syntax.docCommentPrefix, i) && !text.startsWith("/**/", i)
                if (!isDoc) {
                    val span = Span(i, end)
                    val removal = if (syntax.jsx && open == "/*") jsxExpression(text, span) ?: span else span
                    tokens += CommentToken(CommentKind.BLOCK, span, closeAt, text.substring(i + open.length, closeAt), removal)
                }
                i = end
                continue@loop
            }
            for (prefix in lineComments) {
                if (!text.startsWith(prefix, i)) continue
                if (prefix == "#" && syntax.lineHashNeedsSpace && i > 0 && !text[i - 1].isWhitespace()) continue
                var end = i
                while (end < text.length && text[end] != '\n') end++
                if (end > i && text[end - 1] == '\r') end--
                tokens += CommentToken(CommentKind.LINE, Span(i, end), end, text.substring(i + prefix.length, end))
                i = end
                continue@loop
            }
            i++
        }
        return tokens
    }

    /**
     * The idiomatic JSX comment `{/* … */}`, with braces tight around the comment and placed after a tag (`>`, but not
     * an arrow's `=>`) or another expression (`}`). A code block holding only a comment is formatted with spaces or
     * line breaks inside its braces, so it is never matched and keeps them.
     */
    private fun jsxExpression(text: CharSequence, comment: Span): Span? {
        val open = comment.start - 1
        if (open < 0 || text[open] != '{' || text.getOrNull(comment.end) != '}') return null
        var before = open - 1
        while (before >= 0 && text[before].isWhitespace()) before--
        val previous = text.getOrNull(before)
        val afterTag = previous == '>' && text.getOrNull(before - 1) != '='
        return if (afterTag || previous == '}') Span(open, comment.end + 1) else null
    }

    /** Returns the offset after the closing delimiter; an unclosed single-line string ends at the line break. */
    private fun skipString(text: CharSequence, from: Int, close: String, multiLine: Boolean): Int {
        var i = from
        while (i < text.length) {
            val char = text[i]
            when {
                char == '\\' -> i += 2
                text.startsWith(close, i) -> return i + close.length
                char == '\n' && !multiLine -> return i
                else -> i++
            }
        }
        return text.length
    }

    private fun indexOf(text: CharSequence, needle: String, from: Int): Int {
        var i = from
        while (i <= text.length - needle.length) {
            if (text.startsWith(needle, i)) return i
            i++
        }
        return -1
    }
}

/** Parses [text] as the file at [path]; null when the file type is not supported. */
fun parseAiComments(path: String, text: CharSequence): AiCommentParseResult? {
    val syntax = CommentLanguages.forPath(path) ?: return null
    return AiCommentParser.parse(text, CommentLexer.tokenize(text, syntax))
}
