import * as kotlin from '../lib/ai-comments-cli';

// Run by the pre-commit wrapper (see hooks.ts) in the root of the worktree being committed. With `git commit -a`, git
// points GIT_INDEX_FILE at a temporary index, which the git calls made here inherit and git reads back afterwards.
const summary = kotlin.dev.warin.aicomments.cli.stripForCommit(process.cwd(), false);
if (summary) {
    process.stderr.write(`AI Comments: ${summary}\n`);
}
