package dev.warin.aicomments.cli

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitStripperTest {
    private lateinit var repo: String

    @BeforeTest
    fun createRepo() {
        repo = PosixHost.writeTemp(ByteArray(0)).also { PosixHost.delete(it) }
        sh("mkdir -p '$repo' && git -C '$repo' init -q && git -C '$repo' config user.email t@t && git -C '$repo' config user.name t")
    }

    @AfterTest
    fun removeRepo() {
        sh("rm -rf '$repo'")
    }

    @Test
    fun `strips index and working tree of a partially staged file separately`() {
        // Given a file whose staged version and working-tree version both hold different AI comments
        write("a.ts", "a();\n// — staged note —\n")
        sh("git -C '$repo' add a.ts")
        write("a.ts", "a();\n// — staged note —\nb(); // — unstaged note —\n")

        // When a plain commit is about to run
        val report = hook("git commit -m x")

        // Then each side loses its own AI comments and keeps its own code, so the unstaged change stays unstaged
        assertEquals("a();\n", git("show", ":a.ts"))
        assertEquals("a();\nb();\n", read("a.ts"))
        assertTrue("a.ts" in report, report)
    }

    @Test
    fun `strips files that the same command adds`() {
        // Given a new file with an AI comment that is not staged yet
        write("b.ts", "b(); // — new —\n")

        // When `git add && git commit` is about to run
        hook("git add b.ts && git commit -m x")

        // Then the working tree is stripped, so the add stages the clean file
        assertEquals("b();\n", read("b.ts"))
    }

    @Test
    fun `leaves files outside the commit alone`() {
        // Given one staged file and one unrelated modified file, both with AI comments
        write("c.ts", "c();\n")
        write("d.ts", "d();\n")
        sh("git -C '$repo' add . && git -C '$repo' commit -qm init")
        write("c.ts", "c(); // — in commit —\n")
        write("d.ts", "d(); // — not in commit —\n")
        sh("git -C '$repo' add c.ts")

        // When a plain commit is about to run
        hook("git commit -m x")

        // Then only the committed file is stripped, so review notes on unrelated work survive
        assertEquals("c();\n", read("c.ts"))
        assertEquals("d(); // — not in commit —\n", read("d.ts"))
    }

    @Test
    fun `reports unterminated comments without touching them`() {
        // Given a staged file with an opener that never closes
        write("e.ts", "// — never closed\ne();\n")
        sh("git -C '$repo' add e.ts")

        // When a commit is about to run
        val report = hook("git commit -m x")

        // Then the file is untouched and Claude is told where the leak is
        assertEquals("// — never closed\ne();\n", read("e.ts"))
        assertTrue("e.ts:1" in report, report)
    }

    private fun hook(command: String): String {
        val input = """{"tool_name":"Bash","cwd":"$repo","tool_input":{"command":${kotlinx.serialization.json.JsonPrimitive(command)}}}"""
        return Hooks.preToolUse(input, PosixHost).orEmpty()
    }

    private fun write(path: String, text: String) = PosixHost.writeBytes("$repo/$path", text.encodeToByteArray())

    private fun read(path: String) = PosixHost.readBytes("$repo/$path")!!.decodeToString()

    private fun git(vararg args: String) = PosixHost.git(repo, *args).text

    private fun sh(command: String) {
        check(platform.posix.system(command) == 0) { command }
    }
}
