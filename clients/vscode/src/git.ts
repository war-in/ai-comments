import * as vscode from 'vscode';

// The subset of the built-in Git extension's API (extensions/git/src/api/git.d.ts in VS Code) this extension uses.

export interface Repository {
    readonly rootUri: vscode.Uri;
    readonly onDidCommit?: vscode.Event<void>;
}

export interface GitApi {
    readonly repositories: Repository[];
    readonly onDidOpenRepository: vscode.Event<Repository>;
}

export async function getGitApi(): Promise<GitApi | undefined> {
    const extension = vscode.extensions.getExtension<{getAPI(version: 1): GitApi}>('vscode.git');
    if (!extension) {
        return undefined;
    }
    const exports = extension.isActive ? extension.exports : await extension.activate();
    return exports.getAPI(1);
}
