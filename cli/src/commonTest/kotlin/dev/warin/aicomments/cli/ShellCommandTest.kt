package dev.warin.aicomments.cli

import dev.warin.aicomments.cli.StripScope.Staged
import dev.warin.aicomments.cli.StripScope.WorkingTree
import kotlin.test.Test
import kotlin.test.assertEquals

class ShellCommandTest {
    private val cwd = "/repo"

    @Test
    fun `ignores commands that do not commit`() {
        // Given commands that only mention git or commit in passing
        // When they are planned
        // Then nothing is stripped, so the hook stays out of unrelated commands
        assertEquals(CommitPlan.None, ShellCommand.plan("git status && git log --oneline", cwd))
        assertEquals(CommitPlan.None, ShellCommand.plan("echo 'git commit'", cwd))
    }

    @Test
    fun `strips the index for a plain commit`() {
        // Given a commit of what is already staged
        // When it is planned
        // Then only the staged files are stripped
        assertEquals(CommitPlan.Exact(listOf(Staged(cwd))), ShellCommand.plan("git commit -m 'Fix'", cwd))
    }

    @Test
    fun `strips files that a preceding add will stage`() {
        // Given an add and a commit in one command, which the hook sees before the add has run
        val plan = ShellCommand.plan("git add src/a.ts src/b.ts && git commit -m \"Fix\"", cwd)

        // When it is planned
        // Then the added paths are stripped in the working tree so they get staged already clean
        assertEquals(CommitPlan.Exact(listOf(WorkingTree(cwd, listOf("src/a.ts", "src/b.ts"), true), Staged(cwd))), plan)
    }

    @Test
    fun `keeps the usual heredoc commit message opaque`() {
        // Given the heredoc-in-substitution form Claude Code commits with, including parentheses and quotes in the message
        val command = "git add -A && git commit -m \"\$(cat <<'EOF'\nFix (things) that \"broke\"\n\nCo-Authored-By: x\nEOF\n)\""

        // When it is planned
        // Then it is understood exactly instead of falling back to stripping everything
        assertEquals(CommitPlan.Exact(listOf(WorkingTree(cwd, listOf(":/"), true), Staged(cwd))), ShellCommand.plan(command, cwd))
    }

    @Test
    fun `understands -a combined with -m`() {
        // Given `-am`, where `a` stages all tracked changes and `m` takes the message
        // When it is planned
        // Then all tracked modified files are stripped, and the message is not mistaken for a pathspec
        assertEquals(
            CommitPlan.Exact(listOf(Staged(cwd), WorkingTree(cwd, listOf(":/"), false))),
            ShellCommand.plan("git commit -am 'src/a.ts'", cwd),
        )
    }

    @Test
    fun `follows cd and -C`() {
        // Given commits in another directory, reached by cd and by git -C
        // When they are planned
        // Then each is stripped in the directory it runs in
        assertEquals(CommitPlan.Exact(listOf(Staged("/repo/app"))), ShellCommand.plan("cd app && git commit -m x", cwd))
        assertEquals(CommitPlan.Exact(listOf(Staged("/other"))), ShellCommand.plan("git -C /other commit -m x", cwd))
    }

    @Test
    fun `ignores redirections`() {
        // Given output redirections after the commit
        // When it is planned
        // Then their targets are not taken for pathspecs
        assertEquals(CommitPlan.Exact(listOf(Staged(cwd))), ShellCommand.plan("git commit -m x > /tmp/out 2>&1", cwd))
    }

    @Test
    fun `commits with pathspecs strip those files`() {
        // Given a commit that names files, which commits their working-tree content
        // When it is planned
        // Then those files are stripped in the working tree
        assertEquals(
            CommitPlan.Exact(listOf(Staged(cwd), WorkingTree(cwd, listOf("a.ts"), false))),
            ShellCommand.plan("git commit -m msg -- a.ts", cwd),
        )
    }

    @Test
    fun `falls back when the command can't be understood`() {
        // Given commits hidden behind eval, backticks or sh -c
        // When they are planned
        // Then the plan is unknown, so everything changed gets stripped rather than risking a leak
        assertEquals(CommitPlan.Unknown(cwd), ShellCommand.plan("eval 'git commit -m x'", cwd))
        assertEquals(CommitPlan.Unknown(cwd), ShellCommand.plan("git commit -m `date`", cwd))
        assertEquals(CommitPlan.Unknown(cwd), ShellCommand.plan("sh -c 'git commit -m x'", cwd))
    }
}
