import {execFile} from 'child_process';
import {promisify} from 'util';
import * as vscode from 'vscode';

import * as kotlin from '../lib/ai-comments-cli';
import {getGitApi, GitApi, Repository} from './git';

/** Grammar, lexer, stripper and commit stripping all come from the shared Kotlin core, compiled to JS. */
const core = kotlin.dev.warin.aicomments.cli;
type Analysis = kotlin.dev.warin.aicomments.cli.JsAnalysis;

const MAX_DOCUMENT_LENGTH = 2_000_000;
const execFileAsync = promisify(execFile);

export async function activate(context: vscode.ExtensionContext) {
    const decoration = vscode.window.createTextEditorDecorationType({
        backgroundColor: new vscode.ThemeColor('aiComments.background'),
        color: new vscode.ThemeColor('aiComments.foreground'),
        rangeBehavior: vscode.DecorationRangeBehavior.ClosedClosed,
    });
    // Diff editors disable variable fonts and then drop any decoration that sets a font property, colors included.
    const italic = vscode.window.createTextEditorDecorationType({fontStyle: 'italic', rangeBehavior: vscode.DecorationRangeBehavior.ClosedClosed});
    const diagnostics = vscode.languages.createDiagnosticCollection('ai-comments');
    const output = vscode.window.createOutputChannel('AI Comments');
    const analyses = new Map<string, {version: number; analysis: Analysis}>();
    const decorated = new WeakMap<vscode.TextEditor, number>();
    context.subscriptions.push(decoration, italic, diagnostics, output);

    function analysisOf(document: vscode.TextDocument): Analysis | undefined {
        const key = document.uri.toString();
        const cached = analyses.get(key);
        if (cached?.version === document.version) {
            return cached.analysis;
        }
        const text = document.getText();
        if (text.length > MAX_DOCUMENT_LENGTH) {
            return undefined;
        }
        const analysis = core.analyze(document.fileName, text);
        analyses.set(key, {version: document.version, analysis});
        return analysis;
    }

    function refresh(document: vscode.TextDocument) {
        const analysis = analysisOf(document);
        const highlights = (analysis?.highlights ?? []).map((range) => new vscode.Range(document.positionAt(range.start), document.positionAt(range.end)));
        for (const editor of vscode.window.visibleTextEditors) {
            if (editor.document === document) {
                editor.setDecorations(decoration, highlights);
                editor.setDecorations(italic, highlights);
                decorated.set(editor, highlights.length);
            }
        }
        // Only real files get problems: the HEAD side of a diff is never edited, so warning there is noise.
        if (document.uri.scheme !== 'file' && document.uri.scheme !== 'untitled') {
            return;
        }
        diagnostics.set(
            document.uri,
            (analysis?.problems ?? []).map((problem) => {
                const range = new vscode.Range(document.positionAt(problem.start), document.positionAt(problem.end));
                const diagnostic = new vscode.Diagnostic(range, problem.message, vscode.DiagnosticSeverity.Warning);
                diagnostic.source = 'AI Comments';
                return diagnostic;
            }),
        );
    }

    let pending: NodeJS.Timeout | undefined;
    const refreshVisible = () => {
        clearTimeout(pending);
        pending = setTimeout(() => new Set(vscode.window.visibleTextEditors.map((editor) => editor.document)).forEach(refresh), 150);
    };
    refreshVisible();
    context.subscriptions.push(
        vscode.window.onDidChangeVisibleTextEditors(refreshVisible),
        vscode.workspace.onDidChangeTextDocument(refreshVisible),
        vscode.workspace.onDidCloseTextDocument((document) => {
            analyses.delete(document.uri.toString());
            diagnostics.delete(document.uri);
        }),
    );

    context.subscriptions.push(
        vscode.languages.registerCodeActionsProvider({scheme: 'file'}, new CloseAiCommentActions(analysisOf), {
            providedCodeActionKinds: [vscode.CodeActionKind.QuickFix],
        }),
        vscode.commands.registerTextEditorCommand('aiComments.stripFile', (editor) => stripDocument(editor.document)),
    );

    // For the integration test: how many AI comments each visible editor was decorated with.
    const api = {decorations: () => vscode.window.visibleTextEditors.map((editor) => ({uri: editor.document.uri.toString(), count: decorated.get(editor)}))};

    const git = await getGitApi();
    if (!git) {
        output.appendLine('The built-in Git extension is disabled, so AI comments are not stripped on commit.');
        return api;
    }
    const committer = new Committer(git, output);
    context.subscriptions.push(vscode.commands.registerCommand('aiComments.commit', (arg?: unknown) => committer.commit(arg)));
    git.repositories.forEach((repository) => committer.watch(repository, context));
    context.subscriptions.push(git.onDidOpenRepository((repository) => committer.watch(repository, context)));
    return api;
}

export function deactivate() {}

async function stripDocument(document: vscode.TextDocument) {
    const deletions = core.deletions(document.fileName, document.getText());
    if (deletions.length === 0) {
        vscode.window.setStatusBarMessage('AI Comments: nothing to strip', 3000);
        return;
    }
    const edit = new vscode.WorkspaceEdit();
    for (const range of deletions) {
        edit.delete(document.uri, new vscode.Range(document.positionAt(range.start), document.positionAt(range.end)));
    }
    await vscode.workspace.applyEdit(edit);
}

class CloseAiCommentActions implements vscode.CodeActionProvider {
    constructor(private readonly analysisOf: (document: vscode.TextDocument) => Analysis | undefined) {}

    provideCodeActions(document: vscode.TextDocument, range: vscode.Range): vscode.CodeAction[] {
        const offset = document.offsetAt(range.start);
        const problem = this.analysisOf(document)?.problems.find((it) => it.closeAt != null && it.start <= offset && offset <= it.end);
        if (problem?.closeAt == null) {
            return [];
        }
        const text = document.getText();
        const needsSpace = problem.closeAt > 0 && !/\s/.test(text[problem.closeAt - 1]);
        const action = new vscode.CodeAction('Close AI comment', vscode.CodeActionKind.QuickFix);
        action.edit = new vscode.WorkspaceEdit();
        action.edit.insert(document.uri, document.positionAt(problem.closeAt), needsSpace ? ' —' : '—');
        action.isPreferred = true;
        return [action];
    }
}

/**
 * VS Code has no pre-commit extension point, so stripping happens in a command that then runs the regular
 * `git.commit` flow, bound to Cmd/Ctrl+Enter in the Source Control input and shown as the Source Control title button.
 */
class Committer {
    private ownCommits = 0;

    constructor(
        private readonly git: GitApi,
        private readonly output: vscode.OutputChannel,
    ) {}

    async commit(arg?: unknown) {
        const repository = this.repositoryFor(arg);
        if (!repository) {
            await vscode.commands.executeCommand('scm.acceptInput');
            return;
        }
        await vscode.workspace.saveAll(false);
        // With nothing staged, VS Code's commit offers to stage everything, so the working tree is stripped too.
        const nothingStaged = repository.state.indexChanges.length === 0;
        try {
            const summary = core.stripForCommit(repository.rootUri.fsPath, nothingStaged);
            if (summary) {
                this.output.appendLine(summary);
                vscode.window.setStatusBarMessage(`AI Comments: ${summary}`, 8000);
            }
        } catch (error) {
            const proceed = await vscode.window.showErrorMessage(`AI Comments could not strip before commit: ${error}`, 'Commit anyway');
            if (proceed !== 'Commit anyway') {
                return;
            }
        }
        this.ownCommits++;
        await vscode.commands.executeCommand('git.commit', repository.rootUri);
    }

    /** Catches commits made around the command, like the Commit button, and says when AI comments leaked into them. */
    watch(repository: Repository, context: vscode.ExtensionContext) {
        if (!repository.onDidCommit) {
            return;
        }
        context.subscriptions.push(
            repository.onDidCommit(async () => {
                if (this.ownCommits > 0) {
                    this.ownCommits--;
                    return;
                }
                if (!vscode.workspace.getConfiguration('aiComments').get<boolean>('warnAfterCommit', true)) {
                    return;
                }
                const leaked = await filesWithAiCommentsInHead(repository.rootUri.fsPath);
                if (leaked.length > 0) {
                    vscode.window.showWarningMessage(
                        `The last commit contains AI comments in ${leaked.join(', ')}. Commit with Cmd/Ctrl+Enter or "Commit (Strip AI Comments)" to strip them.`,
                    );
                }
            }),
        );
    }

    private repositoryFor(arg: unknown): Repository | undefined {
        const rootUri = (arg as {rootUri?: vscode.Uri} | undefined)?.rootUri;
        if (rootUri) {
            return this.git.getRepository(rootUri) ?? undefined;
        }
        const repositories = this.git.repositories;
        const active = vscode.window.activeTextEditor?.document.uri;
        return (
            repositories.find((repository) => repository.ui.selected) ??
            (active ? (this.git.getRepository(active) ?? undefined) : undefined) ??
            (repositories.length === 1 ? repositories[0] : undefined)
        );
    }
}

async function filesWithAiCommentsInHead(root: string): Promise<string[]> {
    const git = async (...args: string[]) => (await execFileAsync('git', ['-C', root, ...args], {maxBuffer: 256 * 1024 * 1024})).stdout;
    try {
        const files = (await git('diff-tree', '--no-commit-id', '--name-only', '-r', '-z', '--diff-filter=AM', 'HEAD')).split('\0').filter(Boolean);
        const leaked: string[] = [];
        for (const file of files) {
            const analysis = core.analyze(file, await git('show', `HEAD:${file}`));
            if (analysis.highlights.length > 0) {
                leaked.push(file);
            }
        }
        return leaked;
    } catch {
        return [];
    }
}
