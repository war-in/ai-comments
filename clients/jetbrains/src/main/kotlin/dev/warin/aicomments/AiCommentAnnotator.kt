package dev.warin.aicomments

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.openapi.util.TextRange

object AiCommentColors {
    // The fallback keeps AI comments distinguishable in themes that define no color for them.
    val AI_COMMENT = TextAttributesKey.createTextAttributesKey("AI_COMMENTS.AI_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE)
}

class AiCommentAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile ?: return
        val range = element.textRange
        // Comments are annotated one by one; without comment PSI, the file element takes everything the lexer found.
        val matches: (Span) -> Boolean = when {
            element is PsiComment -> { span -> span.start == range.startOffset }
            element is PsiFile && AiCommentFinder.usesLexer(element) -> { _ -> true }
            else -> return
        }
        val result = AiCommentFinder.find(file)

        for (comment in result.comments) {
            comment.parts.filter(matches).forEach { part ->
                val partRange = TextRange(part.start, part.end)
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(partRange).textAttributes(AiCommentColors.AI_COMMENT).create()
                if (part == comment.parts.first() && !AiCommentFinder.isSupportedEverywhere(file)) {
                    holder.newAnnotation(HighlightSeverity.WARNING, "AI comments aren't supported in ${file.name} outside the IDE: commits made by Claude won't strip it")
                        .range(partRange)
                        .create()
                }
            }
        }

        for (unterminated in result.unterminated.filter { matches(it.span) }) {
            holder.newAnnotation(HighlightSeverity.WARNING, "Unterminated AI comment: it will not be stripped on commit")
                .range(TextRange(unterminated.span.start, unterminated.span.end))
                .withFix(AddClosingDashFix(unterminated.closeAt))
                .create()
        }
    }
}

class AddClosingDashFix(private val offset: Int) : BaseIntentionAction() {
    override fun getFamilyName() = "Close AI comment"

    override fun getText() = familyName

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = editor != null && offset <= editor.document.textLength

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = editor?.document ?: return
        val needsSpace = offset > 0 && !document.charsSequence[offset - 1].isWhitespace()
        document.insertString(offset, if (needsSpace) " ${AiCommentParser.DASH}" else "${AiCommentParser.DASH}")
    }
}

class AiCommentColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "AI Comments"

    override fun getIcon() = null

    override fun getHighlighter() = PlainSyntaxHighlighter()

    override fun getDemoText() = """
        |const draft = useDraft();
        |<ai>// — Moved out of the effect so rerenders do not refire the request —</ai>
        |const request = useRequest(draft);
        |// A regular comment: the API rejects empty arrays.
        |""".trimMargin()

    override fun getAdditionalHighlightingTagToDescriptorMap() = mapOf("ai" to AiCommentColors.AI_COMMENT)

    override fun getAttributeDescriptors() = arrayOf(AttributesDescriptor("AI comment", AiCommentColors.AI_COMMENT))

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
}
