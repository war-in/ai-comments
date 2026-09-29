package dev.warin.aicomments

import kotlin.test.Test
import kotlin.test.assertEquals

class AiCommentParserTest {
    @Test
    fun `strips a full-line comment together with its line`() {
        // Given a whole line holding only an AI comment
        val code = """
            |const a = 1;
            |    // — Moved out of the effect so it does not refire —
            |const b = 2;
            |""".trimMargin()

        // When it is stripped
        // Then the line disappears entirely, leaving no blank line behind
        assertEquals("const a = 1;\nconst b = 2;\n", strip(code))
    }

    @Test
    fun `strips a trailing comment and keeps the code`() {
        // Given an AI comment after code on the same line
        val code = "foo(); // — Was bar(), which mutates input —\nnext();\n"

        // When it is stripped
        // Then the code and the line break survive, and so does nothing else from the comment
        assertEquals("foo();\nnext();\n", strip(code))
    }

    @Test
    fun `strips an inline block comment and the space after it`() {
        // Given a block AI comment in the middle of an expression
        val code = "call(/* — was 2 — */ 3);\n"

        // When it is stripped
        // Then the argument closes up to the parenthesis
        assertEquals("call(3);\n", strip(code))
    }

    @Test
    fun `strips a multi-line block comment with star decorations`() {
        // Given a block AI comment whose continuation lines use the conventional leading `*`
        val code = """
            |a();
            |/* — Split into two effects: the first syncs the draft,
            | * the second persists it, so typing does not trigger a save —
            | */
            |b();
            |""".trimMargin()

        // When it is stripped
        // Then all its lines go, because the `*` is decoration and not part of the body
        assertEquals("a();\nb();\n", strip(code))
    }

    @Test
    fun `strips a line-comment group from opener to closer only`() {
        // Given regular comments directly around a multi-line AI comment
        val code = """
            |// Keep: the API rejects empty arrays.
            |// — Split into two effects: the first syncs the draft,
            |// the second persists it —
            |// Keep too.
            |save();
            |""".trimMargin()

        // When it is stripped
        // Then only the lines between the dashes go, since the neighbours are not AI comments
        assertEquals("// Keep: the API rejects empty arrays.\n// Keep too.\nsave();\n", strip(code))
    }

    @Test
    fun `accepts a closing dash on its own line`() {
        // Given a group whose last line holds only the closing dash
        val code = "// — first\n// second\n// —\nx();\n"

        // When it is stripped
        // Then the whole group goes
        assertEquals("x();\n", strip(code))
    }

    @Test
    fun `leaves an unterminated opener and reports where to close it`() {
        // Given a group that opens but never closes, which usually means the AI forgot the dash
        val code = "// — first\n// second\nx();\n"

        // When it is parsed
        val result = parse(code)

        // Then nothing is stripped, since eating a regular comment would be worse than keeping an AI one,
        // and the suggested closing point is the end of the group rather than of the opener
        assertEquals(emptyList<AiComment>(), result.comments)
        assertEquals(listOf(UnterminatedAiComment(Span(0, 10), code.indexOf("\nx()"))), result.unterminated)
    }

    @Test
    fun `does not join line comments separated by a blank line`() {
        // Given an opener whose would-be closer sits after an empty line
        val code = "// — first\n\n// second —\n"

        // When it is parsed
        val result = parse(code)

        // Then the blank line breaks the group, so neither line is an AI comment
        assertEquals(emptyList<AiComment>(), result.comments)
        assertEquals(1, result.unterminated.size)
    }

    @Test
    fun `ignores prose em-dashes`() {
        // Given regular comments that use em-dashes the way prose does
        val code = """
            |// Hermes drops frames — see #1234
            |// Values — both keys and ids — are strings
            |/* —— */
            |// —no space—
            |""".trimMargin()

        // When it is parsed
        val result = parse(code)

        // Then none of them count, because the dashes are not delimiters followed or preceded by whitespace
        assertEquals(emptyList<AiComment>(), result.comments)
        assertEquals(emptyList<UnterminatedAiComment>(), result.unterminated)
    }

    @Test
    fun `strips the wrapper when one is given`() {
        // Given a JSX comment whose braces the PSI layer reports as part of the removal
        val code = "<View>\n    {/* — Wrapped so the whole badge is pressable — */}\n    <Text/>\n</View>\n"
        val start = code.indexOf("/*")
        val end = code.indexOf("*/") + 2
        val token = CommentToken(CommentKind.BLOCK, Span(start, end), end - 2, code.substring(start + 2, end - 2), Span(start - 1, end + 1))

        // When it is stripped
        val result = AiCommentParser.parse(code, listOf(token))

        // Then the braces go along with the comment and the line disappears
        assertEquals("<View>\n    <Text/>\n</View>\n", AiCommentStripper.strip(code, result.comments))
    }

    @Test
    fun `strips a comment at the end of a file without a trailing newline`() {
        // Given an AI comment on the last line and no final line break
        val code = "a();\n// — note —"

        // When it is stripped
        // Then the preceding line break goes too, instead of leaving an empty last line
        assertEquals("a();", strip(code))
    }

    private fun parse(code: String) = AiCommentParser.parse(code, CommentLexer.tokenize(code, CommentLanguages.forPath("a.ts")!!))

    private fun strip(code: String) = AiCommentStripper.strip(code, parse(code).comments)
}
