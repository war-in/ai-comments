package dev.warin.aicomments

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AiCommentFinderTest : BasePlatformTestCase() {
    fun `test strips a comment-only JSX expression with its braces`() {
        // Given a TSX file with an AI comment as a JSX child
        val file = myFixture.configureByText(
            "a.tsx",
            "const a = (\n    <View>\n        {/* — Wrapped so the whole badge is pressable — */}\n        <Text/>\n    </View>\n);\n",
        )

        // When it is stripped
        val stripped = AiCommentStripper.strip(file.text, AiCommentFinder.find(file).comments)

        // Then the braces go too, because empty braces would be left behind otherwise
        assertEquals("const a = (\n    <View>\n        <Text/>\n    </View>\n);\n", stripped)
    }

    fun `test keeps the braces of a code block`() {
        // Given a TS code block holding only an AI comment, which looks like a JSX expression as text
        val file = myFixture.configureByText("a.ts", "if (x) { /* — keep braces — */ }\n")

        // When it is stripped
        val stripped = AiCommentStripper.strip(file.text, AiCommentFinder.find(file).comments)

        // Then the block survives, since removing it would break the code
        assertEquals("if (x) { }\n", stripped)
    }

    fun `test ignores JSDoc and detects line groups in TS`() {
        // Given a JSDoc that looks like an AI comment and a line group
        val file = myFixture.configureByText(
            "a.ts",
            "/** — documented — */\nfunction f() {}\n// — first\n// second —\nf();\n",
        )

        // When it is stripped
        val stripped = AiCommentStripper.strip(file.text, AiCommentFinder.find(file).comments)

        // Then JSDoc is kept as API documentation and the group goes
        assertEquals("/** — documented — */\nfunction f() {}\nf();\n", stripped)
    }

    fun `test detects AI comments in shell scripts`() {
        // Given a shell script, whose commenter reports `#` as the doc comment prefix too
        val file = myFixture.configureByText("a.sh", "#!/bin/bash\n# — Was sh —\necho hi # — trailing —\n")

        // When it is stripped
        val stripped = AiCommentStripper.strip(file.text, AiCommentFinder.find(file).comments)

        // Then both comments go, since shell has no doc comments to keep
        assertFalse(AiCommentFinder.usesLexer(file))
        assertEquals("#!/bin/bash\necho hi\n", stripped)
    }

    fun `test falls back to the lexer for files the IDE treats as plain text`() {
        // Given a Swift file, which WebStorm has no comment PSI for
        val file = myFixture.configureByText("a.swift", "let a = 1 // — Was 2 —\nlet b = \"// — in a string —\"\n")

        // When it is stripped
        val stripped = AiCommentStripper.strip(file.text, AiCommentFinder.find(file).comments)

        // Then the core lexer finds the comment and still skips the string
        assertTrue(AiCommentFinder.usesLexer(file))
        assertEquals("let a = 1\nlet b = \"// — in a string —\"\n", stripped)
    }
}
