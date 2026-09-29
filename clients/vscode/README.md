# AI Comments for VS Code

Highlights transient AI comments, delimited by em-dashes, and strips them when you commit, so they never reach git history.

```ts
// — Moved out of the effect so rerenders do not refire the request —
```

- **Highlighting** in editors and in the working-tree side of diffs. Colors: `aiComments.background` and `aiComments.foreground` in `workbench.colorCustomizations`.
- **Warnings** for unterminated AI comments (with a quick fix to close them), and for AI comments in file types they can't be stripped from.
- **Stripping on commit**: every commit VS Code makes (the Commit button, `Cmd/Ctrl+Enter`, Commit & Push, …) strips the index and working tree of what the commit includes first. Toggle: `aiComments.stripOnCommit`.

VS Code has no pre-commit extension point, so the extension points the built-in Git extension's `core.hooksPath` at a
pre-commit hook that strips, through `GIT_CONFIG_*` environment variables of the extension host. Nothing is written to
your repositories or git config, and your own hooks (including husky's) still run after stripping. Commits that skip
hooks, like Commit (No Verify), aren't stripped; you get a warning when AI comments leak into one.

Part of [ai-comments](https://github.com/war-in/ai-comments), which also has a JetBrains plugin and a Claude Code plugin.
