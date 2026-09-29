import {execFile} from 'child_process';
import * as path from 'path';
import {promisify} from 'util';
import * as vscode from 'vscode';

import * as kotlin from '../lib/ai-comments-cli';
import {getGitApi, Repository} from './git';
import {StripHook} from './hooks';

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

    const stripHook = new StripHook(path.join(context.globalStorageUri.fsPath, 'hooks'), context.asAbsolutePath(path.join('dist', 'strip-hook.js')));
    const applyStripOnCommit = () => {
        if (vscode.workspace.getConfiguration('aiComments').get<boolean>('stripOnCommit', true)) {
            stripHook.enable();
        } else {
            stripHook.disable();
        }
    };
    applyStripOnCommit();
    context.subscriptions.push(
        {dispose: () => stripHook.disable()},
        vscode.workspace.onDidChangeConfiguration((event) => event.affectsConfiguration('aiComments.stripOnCommit') && applyStripOnCommit()),
    );

    const git = await getGitApi();
    if (!git) {
        output.appendLine('The built-in Git extension is disabled, so commits are not checked for AI comments.');
        return api;
    }
    git.repositories.forEach((repository) => warnOnLeaks(repository, context));
    context.subscriptions.push(git.onDidOpenRepository((repository) => warnOnLeaks(repository, context)));
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

/** Catches commits the pre-commit hook did not strip, like ones made with `--no-verify`, and says when AI comments leaked into them. */
function warnOnLeaks(repository: Repository, context: vscode.ExtensionContext) {
    if (!repository.onDidCommit) {
        return;
    }
    context.subscriptions.push(
        repository.onDidCommit(async () => {
            if (!vscode.workspace.getConfiguration('aiComments').get<boolean>('warnAfterCommit', true)) {
                return;
            }
            const leaked = await filesWithAiCommentsInHead(repository.rootUri.fsPath);
            if (leaked.length > 0) {
                vscode.window.showWarningMessage(`The last commit contains AI comments in ${leaked.join(', ')}. The commit skipped the pre-commit hook that strips them, or the Git output shows why it failed.`);
            }
        }),
    );
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
