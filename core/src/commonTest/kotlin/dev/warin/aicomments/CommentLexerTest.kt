package dev.warin.aicomments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentLexerTest {
    @Test
    fun `ignores comment markers inside strings`() {
        // Given markers inside string and template literals, which must not be read as comments
        val code = """
            |const url = "https://example.com/// — not a comment —";
            |const t = `/* — also not — */`;
            |const c = '#'; // — real —
            |""".trimMargin()

        // When it is stripped
        // Then only the real trailing comment goes
        assertEquals(
            "const url = \"https://example.com/// — not a comment —\";\nconst t = `/* — also not — */`;\nconst c = '#';\n",
            strip("a.ts", code),
        )
    }

    @Test
    fun `skips JSDoc`() {
        // Given a JSDoc block whose body looks like an AI comment
        val code = "/** — documented — */\nfunction f() {}\n"

        // When it is stripped
        // Then it is kept, because JSDoc is API documentation that tooling reads
        assertEquals(code, strip("a.ts", code))
    }

    @Test
    fun `strips JSX comments with their braces`() {
        // Given JSX comments as tag children, after a tag and after another expression
        val code = "<View>\n    {/* — Wrapped so the badge is pressable — */}\n    {a}\n    {/* — second — */}\n</View>\n"

        // When the lexer strips them
        // Then the braces go too, so no empty expression is left behind
        assertEquals("<View>\n    {a}\n</View>\n", strip("a.tsx", code))
    }

    @Test
    fun `keeps the braces of code blocks that hold only a comment`() {
        // Given code blocks whose whole body is an AI comment, which JSX-like braces must not be mistaken for
        val code = "const f = () => {/* — stub — */};\nif (x) { /* — spaced — */ }\n"

        // When the lexer strips them
        // Then the braces stay, because removing them would break the code
        assertEquals("const f = () => {};\nif (x) { }\n", strip("a.tsx", code))
    }

    @Test
    fun `handles hash comments only where they start a comment`() {
        // Given YAML with a `#` inside a value and a real comment
        val code = "color: a#b # — not a comment in YAML without the space —\nkey: 1 # — Was 2 —\n"

        // When it is stripped
        // Then `a#b` is kept and both real comments go, since each `#` after whitespace starts a comment
        assertEquals("color: a#b\nkey: 1\n", strip("config.yml", code))
    }

    @Test
    fun `handles XML and SQL comments`() {
        // Given AI comments in markup and in SQL
        val xml = "<a>\n    <!-- — Darkened for contrast — -->\n    <b/>\n</a>\n"
        val sql = "SELECT 1; -- — Was SELECT 2 —\n"

        // When they are stripped
        // Then each language's own comment syntax is recognised
        assertEquals("<a>\n    <b/>\n</a>\n", strip("colors.xml", xml))
        assertEquals("SELECT 1;\n", strip("q.sql", sql))
    }

    @Test
    fun `keeps CRLF line endings intact`() {
        // Given a file with Windows line endings
        val code = "a();\r\n// — note —\r\nb(); // — trailing —\r\n"

        // When it is stripped
        // Then whole lines go with their CRLF, and the kept line keeps its CR
        assertEquals("a();\r\nb();\r\n", strip("a.ts", code))
    }

    @Test
    fun `resolves file names and rejects unknown types`() {
        // Given supported special file names and an unsupported extension
        // When they are looked up
        // Then names without an extension resolve by name, and unknown types stay unsupported so nothing gets stripped
        assertEquals(true, CommentLanguages.isSupported("ios/Podfile"))
        assertEquals(true, CommentLanguages.isSupported(".env.production"))
        assertEquals(true, CommentLanguages.isSupported("src/App.TSX"))
        assertNull(CommentLanguages.forPath("schema.proto"))
        assertNull(CommentLanguages.forPath("LICENSE"))
    }

    private fun strip(path: String, code: String) = AiCommentStripper.strip(code, parseAiComments(path, code)!!.comments)
}
