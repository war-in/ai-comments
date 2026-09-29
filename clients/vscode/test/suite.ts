import * as assert from 'assert';
import {execFileSync} from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';

// Runs inside VS Code with the extension loaded, against the throwaway repository opened as the workspace.

export async function run() {
    const root = vscode.workspace.workspaceFolders![0].uri.fsPath;
    const git = (...args: string[]) => execFileSync('git', ['-C', root, ...args], {encoding: 'utf8'});
    await vscode.extensions.getExtension('war-in.ai-comments')!.activate();
    const gitApi = vscode.extensions.getExtension('vscode.git')!.exports.getAPI(1);
    const repository = await waitFor(() => gitApi.repositories[0]);

    // Given AI comments in an unstaged and a staged change, both diffs decorate their changed side
    const extensionApi = vscode.extensions.getExtension('war-in.ai-comments')!.exports as {decorations(): {uri: string; count?: number}[]};
    fs.writeFileSync(path.join(root, 'README.md'), 'test\n<!-- — Was untitled — -->\n');
    await repository.status();
    await vscode.commands.executeCommand('git.openChange', vscode.Uri.file(path.join(root, 'README.md')));
    await new Promise((resolve) => setTimeout(resolve, 1500));
    assert.deepStrictEqual(decoratedSchemes(extensionApi), ['file'], 'unstaged diff decorates the working-tree side');
    git('add', 'README.md');
    await repository.status();
    await vscode.commands.executeCommand('workbench.action.closeAllEditors');
    await vscode.commands.executeCommand('git.openChange', vscode.Uri.file(path.join(root, 'README.md')));
    await new Promise((resolve) => setTimeout(resolve, 1500));
    assert.deepStrictEqual(decoratedSchemes(extensionApi), ['git'], 'staged diff decorates the index side');
    git('reset', '-q', 'HEAD', 'README.md');
    git('checkout', '-q', '--', 'README.md');
    await vscode.commands.executeCommand('workbench.action.closeAllEditors');

    // Given an unterminated AI comment, the editor reports it with a quick fix to close it
    const open = path.join(root, 'open.ts');
    fs.writeFileSync(open, '// — never closed\nx();\n');
    const document = await vscode.workspace.openTextDocument(open);
    await vscode.window.showTextDocument(document);
    const problems = await waitFor(() => vscode.languages.getDiagnostics(document.uri).filter((it) => it.source === 'AI Comments').at(0));
    const fixes = await vscode.commands.executeCommand<vscode.CodeAction[]>('vscode.executeCodeActionProvider', document.uri, problems.range);
    assert.ok(fixes.some((it) => it.title === 'Close AI comment'), 'quick fix offered');
    fs.rmSync(open);

    // Given the repository's own pre-commit hook, a staged file and an unstaged change, both with AI comments
    const marker = path.join(root, '.git', 'own-hook-ran');
    fs.writeFileSync(path.join(root, '.git', 'hooks', 'pre-commit'), `#!/bin/sh\ntouch "${marker}"\n`, {mode: 0o755});
    fs.writeFileSync(path.join(root, 'a.ts'), 'a();\n// — staged note —\n');
    git('add', 'a.ts');
    fs.writeFileSync(path.join(root, 'a.ts'), 'a();\n// — staged note —\nb(); // — unstaged note —\n');
    await repository.status();

    // When committing with VS Code's own commit, which the Commit button and Cmd+Enter run
    repository.inputBox.value = 'Add a';
    await vscode.commands.executeCommand('git.commit', repository.rootUri);

    // Then the commit and the working tree lose their AI comments, the unstaged change stays unstaged, and the repository's hook still ran
    await waitFor(() => git('log', '--format=%s', '-1').trim() === 'Add a' || undefined);
    assert.strictEqual(git('show', 'HEAD:a.ts'), 'a();\n');
    assert.strictEqual(fs.readFileSync(path.join(root, 'a.ts'), 'utf8'), 'a();\nb();\n');
    assert.strictEqual(git('diff', '--name-only').trim(), 'a.ts');
    assert.ok(fs.existsSync(marker), "the repository's pre-commit hook ran");

    // Given nothing staged, where smart commit stages every change including new files
    await vscode.workspace.getConfiguration('git').update('enableSmartCommit', true, vscode.ConfigurationTarget.Global);
    await vscode.workspace.getConfiguration('git').update('smartCommitChanges', 'all', vscode.ConfigurationTarget.Global);
    fs.writeFileSync(path.join(root, 'c.ts'), 'c(); // — new file —\n');
    await repository.status();

    // When committing with VS Code's own commit
    repository.inputBox.value = 'Smart commit';
    await vscode.commands.executeCommand('git.commit', repository.rootUri);

    // Then everything smart commit picked up was stripped
    await waitFor(() => git('log', '--format=%s', '-1').trim() === 'Smart commit' || undefined);
    assert.strictEqual(git('show', 'HEAD:a.ts'), 'a();\nb();\n');
    assert.strictEqual(git('show', 'HEAD:c.ts'), 'c();\n');
    console.log('AI_COMMENTS_SUITE_PASSED');
}

/** Schemes of the visible editors that got at least one AI comment highlighted. */
function decoratedSchemes(api: {decorations(): {uri: string; count?: number}[]}) {
    return api.decorations().filter((it) => (it.count ?? 0) > 0).map((it) => vscode.Uri.parse(it.uri).scheme);
}

async function waitFor<T>(probe: () => T | undefined, timeoutMs = 20_000): Promise<T> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        const value = probe();
        if (value) {
            return value;
        }
        if (Date.now() > deadline) {
            throw new Error(`Timed out waiting for ${probe}`);
        }
        await new Promise((resolve) => setTimeout(resolve, 200));
    }
}
