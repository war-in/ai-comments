import * as fs from 'fs';
import * as path from 'path';

/**
 * Every hook git looks up by name. `core.hooksPath` replaces the whole hooks directory, so each one gets a wrapper that
 * runs the repository's own hook, or husky's, or whatever `core.hooksPath` pointed at before.
 */
const HOOK_NAMES = [
    'applypatch-msg',
    'pre-applypatch',
    'post-applypatch',
    'pre-commit',
    'pre-merge-commit',
    'prepare-commit-msg',
    'commit-msg',
    'post-commit',
    'pre-rebase',
    'post-checkout',
    'post-merge',
    'pre-push',
    'pre-receive',
    'update',
    'proc-receive',
    'post-receive',
    'post-update',
    'reference-transaction',
    'push-to-checkout',
    'pre-auto-gc',
    'post-rewrite',
    'sendemail-validate',
    'fsmonitor-watchman',
    'p4-changelist',
    'p4-prepare-changelist',
    'p4-post-changelist',
    'p4-pre-submit',
    'post-index-change',
];

// The original hooks directory is resolved with GIT_CONFIG_COUNT as it was before the extension appended its entry.
const WRAPPER = `#!/bin/sh
# Installed by the AI Comments VS Code extension.
name=$(basename "$0")
if [ "$name" = pre-commit ] && [ -n "$AI_COMMENTS_NODE" ]; then
    ELECTRON_RUN_AS_NODE=1 "$AI_COMMENTS_NODE" "$AI_COMMENTS_STRIP_SCRIPT" || echo "AI Comments: could not strip AI comments before this commit" >&2
fi
hooks=$(GIT_CONFIG_COUNT="\${AI_COMMENTS_GIT_CONFIG_COUNT:-0}" git rev-parse --git-path hooks) || exit 0
if [ -x "$hooks/$name" ]; then
    exec "$hooks/$name" "$@"
fi
`;

/**
 * VS Code has no pre-commit extension point, but the built-in Git extension runs in this same extension host and
 * spawns git with a copy of `process.env` taken at spawn time. Config passed through `GIT_CONFIG_*` there points
 * `core.hooksPath` at wrappers whose pre-commit strips, so the Commit button, Cmd/Ctrl+Enter and every other commit
 * VS Code makes strip exactly like `git commit` from Claude does.
 */
export class StripHook {
    private index: number | undefined;
    private readonly originalCount = process.env.GIT_CONFIG_COUNT;

    constructor(
        private readonly hooksDir: string,
        private readonly stripScript: string,
    ) {}

    enable() {
        if (this.index !== undefined) {
            return;
        }
        fs.mkdirSync(this.hooksDir, {recursive: true});
        for (const name of HOOK_NAMES) {
            fs.writeFileSync(path.join(this.hooksDir, name), WRAPPER, {mode: 0o755});
        }
        const index = Number(this.originalCount ?? '0');
        process.env[`GIT_CONFIG_KEY_${index}`] = 'core.hooksPath';
        process.env[`GIT_CONFIG_VALUE_${index}`] = this.hooksDir;
        process.env.GIT_CONFIG_COUNT = String(index + 1);
        process.env.AI_COMMENTS_GIT_CONFIG_COUNT = String(index);
        process.env.AI_COMMENTS_NODE = process.execPath;
        process.env.AI_COMMENTS_STRIP_SCRIPT = this.stripScript;
        this.index = index;
    }

    disable() {
        if (this.index === undefined) {
            return;
        }
        delete process.env[`GIT_CONFIG_KEY_${this.index}`];
        delete process.env[`GIT_CONFIG_VALUE_${this.index}`];
        if (this.originalCount === undefined) {
            delete process.env.GIT_CONFIG_COUNT;
        } else {
            process.env.GIT_CONFIG_COUNT = this.originalCount;
        }
        delete process.env.AI_COMMENTS_GIT_CONFIG_COUNT;
        delete process.env.AI_COMMENTS_NODE;
        delete process.env.AI_COMMENTS_STRIP_SCRIPT;
        this.index = undefined;
    }
}
