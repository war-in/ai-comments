# AI Comments for VS Code

Highlights transient AI comments, delimited by em-dashes, and strips them when you commit, so they never reach git history.

```ts
// — Moved out of the effect so rerenders do not refire the request —
```

- **Highlighting** in editors and in the working-tree side of diffs. Colors: `aiComments.background` and `aiComments.foreground` in `workbench.colorCustomizations`.
- **Warnings** for unterminated AI comments (with a quick fix to close them), and for AI comments in file types they can't be stripped from.
- **Commit (Strip AI Comments)**: `Cmd/Ctrl+Enter` in the Source Control input, or the ✓ button in the Source Control title bar. It strips the index and working tree of what the commit includes, then runs VS Code's normal commit.

VS Code has no pre-commit extension point, so the built-in **Commit** button does not strip. If you use it, a warning tells you
when AI comments leaked into the commit. To avoid the button entirely, set `"git.showActionButton": {"commit": false}`.

Part of [ai-comments](https://github.com/war-in/ai-comments), which also has a JetBrains plugin and a Claude Code plugin.
