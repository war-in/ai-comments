package dev.warin.aicomments

/** Computes the exact text to delete so that stripping leaves no empty lines or dangling spaces behind. */
object AiCommentStripper {
    /** Deletions sorted by descending offset, so they can be applied one after another without shifting. */
    fun deletions(text: CharSequence, comments: List<AiComment>): List<Span> {
        val expanded = comments.map { expand(text, it.removal) }.sortedBy { it.start }
        val merged = mutableListOf<Span>()
        for (span in expanded) {
            val last = merged.lastOrNull()
            if (last != null && span.start <= last.end) {
                merged[merged.lastIndex] = Span(last.start, maxOf(last.end, span.end))
            } else {
                merged += span
            }
        }
        return merged.reversed()
    }

    fun strip(text: CharSequence, comments: List<AiComment>): String {
        val builder = StringBuilder(text)
        deletions(text, comments).forEach { builder.deleteRange(it.start, it.end) }
        return builder.toString()
    }

    private fun expand(text: CharSequence, span: Span): Span {
        var lineStart = span.start
        while (lineStart > 0 && isBlank(text[lineStart - 1])) lineStart--
        val startsLine = lineStart == 0 || text[lineStart - 1] == '\n'

        var lineEnd = span.end
        while (lineEnd < text.length && isBlank(text[lineEnd])) lineEnd++
        val lineBreak = lineBreakLength(text, lineEnd)
        val endsLine = lineEnd == text.length || lineBreak > 0

        return when {
            startsLine && endsLine && lineEnd < text.length -> Span(lineStart, lineEnd + lineBreak)
            startsLine && endsLine && lineStart > 0 -> Span(lineStart - if (lineStart >= 2 && text[lineStart - 2] == '\r') 2 else 1, lineEnd)
            endsLine -> Span(lineStart, lineEnd)
            else -> Span(span.start, lineEnd)
        }
    }

    private fun isBlank(char: Char) = char == ' ' || char == '\t'

    /** Files on disk may use CRLF; IDE documents never do. */
    private fun lineBreakLength(text: CharSequence, offset: Int) = when {
        offset < text.length && text[offset] == '\n' -> 1
        offset + 1 < text.length && text[offset] == '\r' && text[offset + 1] == '\n' -> 2
        else -> 0
    }
}
