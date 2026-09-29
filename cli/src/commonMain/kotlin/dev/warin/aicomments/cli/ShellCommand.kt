package dev.warin.aicomments.cli

/**
 * What a `git commit` inside a shell command will pick up, so the working tree and index can be stripped first.
 *
 * The hook runs before the whole command, so files that a preceding `git add` in the same command will stage
 * are stripped in the working tree now and get staged already stripped.
 */
sealed interface CommitPlan {
    /** The command does not commit. */
    data object None : CommitPlan

    /** The command commits, but could not be understood well enough to know exactly what. */
    data class Unknown(val dir: String) : CommitPlan

    data class Exact(val scopes: List<StripScope>) : CommitPlan
}

sealed interface StripScope {
    val dir: String

    /** Everything already in the index, in both index and working tree. */
    data class Staged(override val dir: String) : StripScope

    /** Working-tree files matching [pathspecs]; tracked modified files, plus untracked ones when [includeUntracked]. */
    data class WorkingTree(override val dir: String, val pathspecs: List<String>, val includeUntracked: Boolean) : StripScope
}

object ShellCommand {
    fun plan(command: String, cwd: String): CommitPlan {
        val segments = split(command) ?: return if (mentionsCommit(command)) CommitPlan.Unknown(cwd) else CommitPlan.None
        var dir = cwd
        val scopes = mutableListOf<StripScope>()
        var commits = false
        for (words in segments) {
            val invocation = words.dropWhile { '=' in it && !it.startsWith("-") }.let { if (it.firstOrNull() == "env") it.drop(1) else it }
            if (invocation.isEmpty()) continue
            when (invocation[0].substringAfterLast('/')) {
                "cd" -> dir = invocation.getOrNull(1)?.takeIf { it != "-" }?.let { resolve(dir, it) }
                    ?: return if (mentionsCommit(command)) CommitPlan.Unknown(cwd) else CommitPlan.None
                "git" -> {
                    val git = parseGit(invocation.drop(1), dir) ?: continue
                    when (git.subcommand) {
                        "add", "stage" -> scopes += addScope(git)
                        "commit" -> {
                            commits = true
                            scopes += StripScope.Staged(git.dir)
                            scopes += commitScopes(git)
                        }
                    }
                }
            }
        }
        return if (commits) CommitPlan.Exact(scopes.distinct()) else CommitPlan.None
    }

    private class GitInvocation(val dir: String, val subcommand: String, val args: List<String>)

    private fun parseGit(words: List<String>, cwd: String): GitInvocation? {
        var dir = cwd
        var i = 0
        while (i < words.size && words[i].startsWith("-")) {
            when (val option = words[i]) {
                "-C" -> {
                    dir = resolve(dir, words.getOrNull(i + 1) ?: return null)
                    i++
                }
                "-c" -> i++
                else -> if (option.startsWith("--git-dir") || option.startsWith("--work-tree")) return null
            }
            i++
        }
        val subcommand = words.getOrNull(i) ?: return null
        return GitInvocation(dir, subcommand, words.drop(i + 1))
    }

    private fun addScope(git: GitInvocation): StripScope {
        val (options, pathspecs) = splitOptions(git.args, optionsWithValue = setOf("--chmod", "--pathspec-from-file"))
        val all = options.any { it == "-A" || it == "--all" || it == "--no-ignore-removal" }
        val updateOnly = options.any { it == "-u" || it == "--update" }
        // Without pathspecs, `-A` and `-u` cover the whole tree, while a plain `git add` adds nothing.
        val whole = (all || updateOnly) && pathspecs.isEmpty()
        return StripScope.WorkingTree(git.dir, if (whole) listOf(":/") else pathspecs, includeUntracked = !updateOnly)
    }

    private fun commitScopes(git: GitInvocation): List<StripScope> {
        val (options, pathspecs) = splitOptions(
            git.args,
            optionsWithValue = setOf("-m", "--message", "-F", "--file", "-C", "--reuse-message", "-c", "--reedit-message",
                "--author", "--date", "--cleanup", "-t", "--template", "--fixup", "--squash", "--trailer"),
        )
        val all = options.any { it == "--all" || (it.startsWith("-") && !it.startsWith("--") && 'a' in shortFlags(it)) }
        return buildList {
            if (all) add(StripScope.WorkingTree(git.dir, listOf(":/"), includeUntracked = false))
            if (pathspecs.isNotEmpty()) add(StripScope.WorkingTree(git.dir, pathspecs, includeUntracked = false))
        }
    }

    /** Short flags up to the first one that takes a value, so `-am msg` is `-a -m` but `-ma` is a message "a". */
    private fun shortFlags(option: String): String {
        val flags = option.drop(1)
        val valueAt = flags.indexOfFirst { it in "mFCct" }
        return if (valueAt < 0) flags else flags.substring(0, valueAt + 1)
    }

    private fun splitOptions(args: List<String>, optionsWithValue: Set<String>): Pair<List<String>, List<String>> {
        val options = mutableListOf<String>()
        val pathspecs = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--" -> {
                    pathspecs += args.drop(i + 1)
                    break
                }
                arg.startsWith("--") -> {
                    options += arg
                    if ('=' !in arg && arg in optionsWithValue) i++
                }
                arg.startsWith("-") && arg.length > 1 -> {
                    options += arg
                    val valueFlag = shortFlags(arg).lastOrNull()?.let { "-$it" }
                    if (valueFlag in optionsWithValue && arg.endsWith(valueFlag!!.drop(1))) i++
                }
                else -> pathspecs += arg
            }
            i++
        }
        return options to pathspecs
    }

    private fun resolve(base: String, path: String) = when {
        path.startsWith("/") -> path
        path.startsWith("~") -> path
        else -> "${base.trimEnd('/')}/$path"
    }

    private fun mentionsCommit(command: String) = Regex("""\bgit\b[^\n;&|]*\bcommit\b""").containsMatchIn(command)

    /**
     * Splits a command into simple commands made of words, or returns null when it contains something that
     * changes what runs in ways a word list can't capture (subshell commands outside quotes, eval, sh -c, xargs).
     * `$(…)` and heredocs inside a word are kept opaque, because commit messages are commonly written that way.
     */
    fun split(command: String): List<List<String>>? {
        val segments = mutableListOf<List<String>>()
        var words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var dropNextWord = false
        val pendingHeredocs = mutableListOf<Pair<String, Boolean>>()
        var i = 0

        fun endWord() {
            if (inWord && !dropNextWord) words += word.toString()
            if (inWord) dropNextWord = false
            word.clear()
            inWord = false
        }

        fun endSegment() {
            endWord()
            if (words.isNotEmpty()) segments += words
            words = mutableListOf()
        }

        fun skipHeredocBodies(): Boolean {
            while (pendingHeredocs.isNotEmpty()) {
                val (delimiter, stripTabs) = pendingHeredocs.removeAt(0)
                while (true) {
                    if (i >= command.length) return false
                    val lineEnd = command.indexOf('\n', i).let { if (it < 0) command.length else it }
                    val line = command.substring(i, lineEnd)
                    i = lineEnd + 1
                    if ((if (stripTabs) line.trimStart('\t') else line) == delimiter) break
                }
            }
            return true
        }

        while (i < command.length) {
            val c = command[i]
            when {
                c == '\\' && i + 1 < command.length -> {
                    if (command[i + 1] != '\n') word.append(command[i + 1])
                    inWord = true
                    i += 2
                }
                c == '\'' -> {
                    val close = command.indexOf('\'', i + 1)
                    if (close < 0) return null
                    word.append(command, i + 1, close)
                    inWord = true
                    i = close + 1
                }
                c == '"' -> {
                    i = readDoubleQuoted(command, i + 1, word) ?: return null
                    inWord = true
                }
                c == '$' && command.startsWith("$(", i) -> {
                    i = skipSubstitution(command, i + 2) ?: return null
                    word.append("\$(…)")
                    inWord = true
                }
                c == '`' -> return null
                c == '<' && command.startsWith("<<", i) && !command.startsWith("<<<", i) -> {
                    endWord()
                    val parsed = readHeredocDelimiter(command, i + 2) ?: return null
                    pendingHeredocs += parsed.first to parsed.second
                    i = parsed.third
                }
                c == '>' || c == '<' -> {
                    // A redirection and its target are not arguments; `2>&1` also drops the fd number before it.
                    if (inWord && word.all { it.isDigit() }) {
                        word.clear()
                        inWord = false
                    }
                    endWord()
                    i++
                    while (i < command.length && (command[i] == '>' || command[i] == '&' || command[i] == '|')) i++
                    dropNextWord = true
                }
                c == '\n' -> {
                    endSegment()
                    i++
                    if (!skipHeredocBodies()) return null
                }
                c == ';' || c == '&' || c == '|' || c == '(' || c == ')' -> {
                    endSegment()
                    i++
                }
                c.isWhitespace() -> {
                    endWord()
                    i++
                }
                else -> {
                    word.append(c)
                    inWord = true
                    i++
                }
            }
        }
        endSegment()
        if (pendingHeredocs.isNotEmpty()) return null
        if (segments.any { it.first() in OPAQUE_COMMANDS || (it.first() in SHELLS && "-c" in it) }) return null
        return segments
    }

    /** Returns the offset after the closing quote; `$(…)` inside is kept opaque. */
    private fun readDoubleQuoted(command: String, from: Int, word: StringBuilder): Int? {
        var i = from
        while (i < command.length) {
            val c = command[i]
            when {
                c == '\\' && i + 1 < command.length -> {
                    word.append(command[i + 1])
                    i += 2
                }
                c == '"' -> return i + 1
                command.startsWith("$(", i) -> {
                    i = skipSubstitution(command, i + 2) ?: return null
                    word.append("\$(…)")
                }
                c == '`' -> return null
                else -> {
                    word.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** Skips to after the `)` matching an opened `$(`, stepping over quotes and heredoc bodies inside it. */
    private fun skipSubstitution(command: String, from: Int): Int? {
        var depth = 1
        var i = from
        val pendingHeredocs = mutableListOf<Pair<String, Boolean>>()
        while (i < command.length) {
            val c = command[i]
            when {
                c == '\\' -> i += 2
                c == '\'' -> i = command.indexOf('\'', i + 1).takeIf { it >= 0 }?.plus(1) ?: return null
                c == '"' -> i = readDoubleQuoted(command, i + 1, StringBuilder()) ?: return null
                command.startsWith("<<", i) && !command.startsWith("<<<", i) -> {
                    val parsed = readHeredocDelimiter(command, i + 2) ?: return null
                    pendingHeredocs += parsed.first to parsed.second
                    i = parsed.third
                }
                c == '\n' && pendingHeredocs.isNotEmpty() -> {
                    i++
                    while (pendingHeredocs.isNotEmpty()) {
                        val (delimiter, stripTabs) = pendingHeredocs.removeAt(0)
                        while (true) {
                            if (i >= command.length) return null
                            val lineEnd = command.indexOf('\n', i).let { if (it < 0) command.length else it }
                            val line = command.substring(i, lineEnd)
                            i = lineEnd + 1
                            if ((if (stripTabs) line.trimStart('\t') else line).trimEnd() == delimiter) break
                        }
                    }
                }
                c == '(' -> {
                    depth++
                    i++
                }
                c == ')' -> {
                    depth--
                    i++
                    if (depth == 0) return i
                }
                else -> i++
            }
        }
        return null
    }

    /** Reads `<<[-]['"]WORD['"]` and returns the delimiter, whether tabs are stripped, and the offset after it. */
    private fun readHeredocDelimiter(command: String, from: Int): Triple<String, Boolean, Int>? {
        var i = from
        val stripTabs = command.getOrNull(i) == '-'
        if (stripTabs) i++
        while (i < command.length && (command[i] == ' ' || command[i] == '\t')) i++
        val quote = command.getOrNull(i)?.takeIf { it == '\'' || it == '"' }
        if (quote != null) i++
        val start = i
        while (i < command.length && (command[i].isLetterOrDigit() || command[i] == '_' || command[i] == '-')) i++
        if (i == start) return null
        val delimiter = command.substring(start, i)
        if (quote != null) {
            if (command.getOrNull(i) != quote) return null
            i++
        }
        return Triple(delimiter, stripTabs, i)
    }

    private val OPAQUE_COMMANDS = setOf("eval", "xargs", "source", ".", "exec")
    private val SHELLS = setOf("sh", "bash", "zsh")
}
