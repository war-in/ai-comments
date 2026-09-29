# AI Comments

AI agents explain their changes in comments that are meant for the reviewer of the diff, not for the codebase.
This toolkit gives those explanations their own syntax, highlights them in the IDE, and strips them automatically
at commit time, so they never reach git history.

```ts
// — Moved out of the effect so rerenders do not refire the request —
const request = useRequest(draft);
```

Regular comments stay what they should be: constraints the code cannot express.

## Install

Both parts work in every project with no per-repository setup.

**JetBrains IDE** (WebStorm, IntelliJ IDEA, Android Studio, … 2025.2 or newer):

Settings | Plugins | Marketplace, search for **AI Comments** and install it.

**VS Code** (1.90 or newer, and forks like Cursor):

Install **AI Comments** (`war-in.ai-comments`) from the Extensions view. Forks get it from Open VSX.

**Claude Code:**

```
/plugin marketplace add war-in/ai-comments
/plugin install ai-comments@war-in
```

This teaches Claude the convention, strips AI comments before every `git commit` Claude runs, and checks every
file Claude edits. On first use it downloads the `ai-comments` binary for your machine from the GitHub release
matching the plugin version, and checks it against the plugin's `checksums.txt`. If you had a copy of the rule in `~/.claude/rules/` or a `CLAUDE.md`, remove it: the plugin injects it.

## The syntax

An AI comment's body starts with `— ` and ends with ` —` (U+2014 em-dash):

| Form | Example |
|---|---|
| Line or trailing | `// — Was reduce() with a shared accumulator —` |
| Block, any length | `/* — first line … last line — */` |
| Line group | consecutive `//` lines from the one opening with `— ` to the one closing with ` —` |
| JSX | `{/* — Wrapped in View so the badge is pressable — */}`: the braces go too |
| Other languages | `# — … —`, `-- — … —`, `` |

- `/** … */` doc comments are never AI comments.
- An opener that never closes is **not** stripped. The IDE shows a warning with a quick-fix, and Claude is told right after the edit.
- Em-dashes inside ordinary prose (`see #123 — it breaks`) don't count.

## What strips, and when

| Commit made by | Stripped by |
|---|---|
| The IDE commit dialog | The JetBrains plugin, before "Reformat code". Toggle: **Strip AI comments** in the commit options |
| VS Code | A pre-commit hook the extension gives VS Code's git (not your repository), so the Commit button, `Cmd/Ctrl+Enter` and every other VS Code commit strip. Your own hooks still run afterwards |
| Claude Code (`git commit`, in any chain) | The Claude plugin's `PreToolUse` hook, which strips the index and working tree of what the commit will include |
| You, in a terminal | Nothing. Commit from the IDE, or strip first with `ai-comments strip <files>` |

Stripping removes AI comments from both the commit and the working tree. A partially staged file keeps its
unstaged changes: the index and working-tree versions are stripped separately. When the command is too
complex to understand (`eval`, `sh -c`, backticks), the hook strips every changed file in the repository instead.

## Supported file types

The lexer's table in `core/src/commonMain/kotlin/dev/warin/aicomments/CommentSyntax.kt` is the single source of
truth: the Claude rule lists these types, the CLI strips only these, and the IDE warns about AI comments elsewhere
(it still highlights and strips them there using the IDE's own parser).

C family and JS/TS (`.ts .tsx .js .kt .java .swift .m .go .rs …`), CSS/SCSS/Less, XML/HTML/SVG/plist/xib/Vue/Svelte,
Markdown/MDX, `#` languages (`.py .rb .sh .yml .toml .properties .env …`, `Dockerfile`, `Podfile`, `Makefile` …),
and SQL/Lua. Run `clients/claude/hooks/run.sh extensions` for the exact list.

The lexer is heuristic: JS regex literals, nested template literals and heredocs can hide a comment from it.
Where it is unsure it misses comments rather than stripping the wrong text.

## Repository layout

```
core/        Kotlin Multiplatform (JVM, native, JS), common code only: grammar, stripper, lexer and file-type table
cli/         `ai-comments` native binary with the Claude Code hook handlers; also a JS library for VS Code
clients/
  jetbrains/ IntelliJ Platform plugin: PSI tokens into core, lexer fallback for files without comment PSI
  vscode/    VS Code extension (TypeScript), using the CLI module compiled to a JS library (clients/vscode/lib)
  claude/    Claude Code plugin: rule template, hooks, and checksums of the release binaries
```

One version number covers everything (`aiCommentsVersion` in `gradle.properties`).

## Development

Gradle downloads the JDK 25 toolchain and the WebStorm version the plugin builds against (`platformVersion` in
`gradle.properties`). The macOS binary needs macOS on Apple silicon; Linux binaries cross-compile from there.

```
./gradlew :core:allTests :cli:allTests :jetbrains:test
./gradlew :jetbrains:runIde -PrunIdeProject=/path/to/repo   # sandbox IDE
./gradlew :cli:copyBinariesToClaudePlugin                   # local binaries in clients/claude/bin, used instead of downloading
claude --plugin-dir ./clients/claude                         # try the Claude plugin without installing it

./gradlew :cli:copyLibraryToVscode                           # rebuild clients/vscode/lib from the Kotlin sources
cd clients/vscode && npm install && npm test                 # integration test; VSCODE_PATH picks an installed VS Code
npm run package                                              # clients/vscode/ai-comments-<version>.vsix
```

Releases run from the **Release** workflow (Actions tab, enter a version). It sets the version everywhere, runs
all tests and the plugin verifier, commits the versions and `clients/claude/checksums.txt`, tags, attaches the plugin
zip, the `.vsix` and the Claude binaries to a GitHub release, and publishes to the JetBrains Marketplace, the VS Code
Marketplace and Open VSX. It needs the secrets `JETBRAINS_MARKETPLACE_TOKEN`, `VSCE_PAT` and `OVSX_PAT`.

Not covered yet: Windows and Intel macOS for the Claude hooks (no binary; they do nothing there).
