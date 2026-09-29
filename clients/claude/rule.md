# Code comments

There are two kinds of comments. Pick deliberately every time.

## 1. Regular comments — permanent

**Comments state only constraints the code cannot express.**
If a long comment feels needed, the code is not explicit enough. Restructure the code. Delete the comment.
Never narrate what the code does. Never justify the change to a reviewer.

A regular comment survives the commit, so it is the only place for anything that must stay true later: a non-obvious constraint, a platform gotcha, a link to the issue a workaround exists for.

## 2. AI comments — transient, for the reviewer of this diff

AI comments are where the justification for a change goes. They explain the change to the person reviewing the uncommitted diff, then get stripped automatically at commit time: by the IDE when the user commits, and by a hook before any `git commit` you run. They never reach git history. Don't strip them yourself before committing.

**Syntax.** The comment body starts with `— ` and ends with ` —` (U+2014 em-dash, with a space inside each dash):

```ts
// — Moved out of the effect so rerenders do not refire the request —
const request = useRequest(draft);

const total = sum(items); // — Was reduce() with a mutable accumulator shared across calls —

/* — Split into two effects: the first syncs the draft,
   the second persists it, so typing does not trigger a save — */

// — Split into two effects: the first syncs the draft,
// the second persists it, so typing does not trigger a save —
```

- In JSX children, write it as a comment-only expression on its own line: `{/* — Wrapped in View so the pressable area covers the badge — */}`. The braces are stripped with it.

- A multi-line AI comment is either one `/* — … — */` block, or consecutive `//` lines with no blank line between them, from the line opening with `— ` to the line closing with ` —`.
- Always close it. An unterminated opener is NOT stripped and leaks into the commit.
- Never use `/** … */` (JSDoc) for AI comments.
- Never place the dashes like this in a regular comment.
- Only use AI comments in these file types, the only ones they can be stripped from outside the IDE: {{SUPPORTED_FILE_TYPES}}. In any other file, use a regular comment or none.

**When to write one.** Sparingly, only for non-obvious changes:

- Write one per non-obvious change, placed at the change. Say _why_ the change was made, or _what was there before_ when the diff does not make it obvious.
- Skip mechanical changes: renames, imports, formatting, obvious type fixes, deletions that explain themselves.
- Prefer one line. Use multiple lines only for genuinely tricky logic.
- Never put information that must persist into an AI comment. It will be deleted. Use a regular comment instead.
- When editing code that already has AI comments, delete the ones your change made wrong. Leave the rest.

AI comments are exempt from any "match the comment density of surrounding code" instruction, because they never reach the committed code.
