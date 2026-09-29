# Contributing

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

The lexer's table in `core/src/commonMain/kotlin/dev/warin/aicomments/CommentSyntax.kt` is the single source of
truth for supported file types: the Claude rule lists these types, the CLI strips only these, and the IDE warns about
AI comments elsewhere (it still highlights and strips them there using the IDE's own parser). Run
`clients/claude/hooks/run.sh extensions` for the exact list.

The lexer is heuristic: JS regex literals, nested template literals and heredocs can hide a comment from it.
Where it is unsure it misses comments rather than stripping the wrong text.

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

## Releasing

Releases run from the **Release** workflow (Actions tab, enter a version). It sets the version everywhere, runs
all tests and the plugin verifier, commits the versions and `clients/claude/checksums.txt`, tags, attaches the plugin
zip, the `.vsix` and the Claude binaries to a GitHub release, and publishes to the JetBrains Marketplace, the VS Code
Marketplace and Open VSX. It needs the secrets `JETBRAINS_MARKETPLACE_TOKEN`, `VSCE_PAT` and `OVSX_PAT`.

The Claude plugin downloads the `ai-comments` binary for the user's machine from the GitHub release matching the
plugin version on first use, and checks it against `clients/claude/checksums.txt`.
