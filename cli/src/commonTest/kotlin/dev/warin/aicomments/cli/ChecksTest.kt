package dev.warin.aicomments.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChecksTest {
    @Test
    fun `flags AI comments in unsupported file types`() {
        // Given an AI comment in a file type the lexer does not know, which nothing would strip before commit
        val problems = Checks.check("schema.proto", "message A {}\n// — Was B —\n")

        // When it is checked
        // Then the line is reported so Claude rewrites it
        assertEquals(1, problems.size)
        assertTrue(problems.single().startsWith("schema.proto:2:"), problems.single())
    }

    @Test
    fun `flags unterminated openers and passes clean files`() {
        // Given a supported file with an unterminated opener, and one with a complete AI comment
        // When they are checked
        // Then only the unterminated one is reported, because complete AI comments are expected in supported files
        assertEquals(1, Checks.check("a.ts", "// — open\nx();\n").size)
        assertEquals(emptyList(), Checks.check("a.ts", "x(); // — fine —\n"))
    }
}
