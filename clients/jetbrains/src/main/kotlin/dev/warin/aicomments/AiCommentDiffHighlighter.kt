package dev.warin.aicomments

import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer

/**
 * Diff editors paint changed lines above annotations, which hides the AI comment background.
 * Each AI comment annotation in a diff editor is copied into that editor's own markup, above the diff but below the selection.
 */
class AiCommentDiffHighlighter : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        if (editor.editorKind != EditorKind.DIFF) return

        val annotations = DocumentMarkupModel.forDocument(editor.document, project, true) as MarkupModelEx
        val copies = HashMap<RangeHighlighter, RangeHighlighter>()
        fun sync(annotation: RangeHighlighter) {
            val isAiComment = annotation.isValid && annotation.textAttributesKey == AiCommentColors.AI_COMMENT
            if (isAiComment && annotation !in copies) {
                copies[annotation] = editor.markupModel.addRangeHighlighter(
                    AiCommentColors.AI_COMMENT,
                    annotation.startOffset,
                    annotation.endOffset,
                    HighlighterLayer.SELECTION - 1,
                    HighlighterTargetArea.EXACT_RANGE,
                )
            } else if (!isAiComment) {
                copies.remove(annotation)?.dispose()
            }
        }

        val disposable = Disposer.newDisposable("AI comments in diff")
        EditorUtil.disposeWithEditor(editor, disposable)
        Disposer.register(disposable) { copies.values.forEach(RangeHighlighter::dispose) }
        annotations.addMarkupModelListener(disposable, object : MarkupModelListener {
            override fun afterAdded(highlighter: RangeHighlighterEx) = sync(highlighter)

            override fun attributesChanged(highlighter: RangeHighlighterEx, renderersChanged: Boolean, fontStyleOrColorChanged: Boolean) = sync(highlighter)

            override fun beforeRemoved(highlighter: RangeHighlighterEx) {
                copies.remove(highlighter)?.dispose()
            }
        })
        annotations.allHighlighters.forEach(::sync)
    }
}
