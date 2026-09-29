import {execFileSync} from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import {runTests} from '@vscode/test-electron';

// Runs in the VS Code at VSCODE_PATH, or a downloaded one, with a fresh profile and no other extensions.
async function main() {
    const repo = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-comments-vscode-'));
    const git = (...args: string[]) => execFileSync('git', ['-C', repo, ...args]);
    git('init', '-q');
    git('config', 'user.email', 't@t');
    git('config', 'user.name', 't');
    fs.writeFileSync(path.join(repo, 'README.md'), 'test\n');
    git('add', '.');
    git('commit', '-qm', 'init');

    const userData = fs.mkdtempSync(path.join(os.tmpdir(), 'ai-comments-vscode-profile-'));
    try {
        await runTests({
            vscodeExecutablePath: process.env.VSCODE_PATH,
            extensionDevelopmentPath: path.resolve(__dirname, '..'),
            extensionTestsPath: path.resolve(__dirname, 'suite.js'),
            launchArgs: [repo, '--disable-extensions', `--user-data-dir=${userData}`, '--skip-welcome', '--skip-release-notes'],
        });
    } finally {
        fs.rmSync(repo, {recursive: true, force: true});
        fs.rmSync(userData, {recursive: true, force: true});
    }
}

main().catch((error) => {
    console.error(error);
    process.exit(1);
});
