package dev.warin.aicomments.cli

import kotlin.test.Test
import kotlin.test.assertEquals

private val childProcess: dynamic = js("require('child_process')")
private val fs: dynamic = js("require('fs')")
private val os: dynamic = js("require('os')")

class NodeHostTest {
    @Test
    fun `strips index and working tree like the native host`() {
        // Given a repository where a staged and an unstaged version of the same file hold different AI comments
        val repo = fs.mkdtempSync("${os.tmpdir()}/ai-comments-test-") as String
        sh("git init -q && git config user.email t@t && git config user.name t", repo)
        write(repo, "a.ts", "a();\n// — staged —\n")
        sh("git add a.ts", repo)
        write(repo, "a.ts", "a();\n// — staged —\nb(); // — unstaged —\n")

        // When VS Code's commit command strips before committing
        val summary = stripForCommit(repo, includeWorkingTree = false)

        // Then each side loses its AI comments and keeps its own code, so the unstaged change stays unstaged
        assertEquals("a();\n", NodeHost.git(repo, "show", ":a.ts").text)
        assertEquals("a();\nb();\n", NodeHost.readBytes("$repo/a.ts")!!.decodeToString())
        assertEquals("Stripped AI comments from a.ts.", summary)
        fs.rmSync(repo, js("({ recursive: true, force: true })"))
    }

    @Test
    fun `reports highlights and problems for the editor`() {
        // Given a TSX snippet with one complete AI comment and one that never closes
        val text = "x(); // — done —\n// — open\ny();\n"

        // When it is analyzed
        val analysis = analyze("a.tsx", text)

        // Then the complete one is highlighted and the open one is a problem with a place to close it
        assertEquals(listOf(5 to 16), analysis.highlights.map { it.start to it.end })
        assertEquals(1, analysis.problems.size)
        assertEquals(text.indexOf("\ny()"), analysis.problems.single().closeAt)
    }

    private fun write(repo: String, path: String, text: String) = fs.writeFileSync("$repo/$path", text)

    private fun sh(command: String, cwd: String) {
        val options: dynamic = js("({})")
        options.cwd = cwd
        childProcess.execSync(command, options)
    }
}
