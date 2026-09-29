package dev.warin.aicomments

import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.LanguageCommenters
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Bridges PSI to [AiCommentParser]; works for any language that reports its comments as [PsiComment].
 * Files without comment PSI (plain text, or TextMate-highlighted like Swift in WebStorm) fall back to the core lexer.
 */
object AiCommentFinder {
    fun find(file: PsiFile): AiCommentParseResult = CachedValuesManager.getCachedValue(file) {
        val text = file.viewProvider.contents
        val tokens = if (usesLexer(file)) lexerTokens(file, text) else tokens(file)
        CachedValueProvider.Result.create(AiCommentParser.parse(text, tokens), file)
    }

    fun usesLexer(file: PsiFile): Boolean = CachedValuesManager.getCachedValue(file) {
        val noCommentPsi = file.viewProvider.allFiles.none { PsiTreeUtil.findChildOfType(it, PsiComment::class.java) != null }
        CachedValueProvider.Result.create(noCommentPsi && CommentLanguages.isSupported(file.name), file)
    }

    /** Whether the CLI and the Claude hook can strip AI comments in this file too. */
    fun isSupportedEverywhere(file: PsiFile) = CommentLanguages.isSupported(file.name)

    private fun lexerTokens(file: PsiFile, text: CharSequence): List<CommentToken> {
        val syntax = CommentLanguages.forPath(file.name) ?: return emptyList()
        return CommentLexer.tokenize(text, syntax)
    }

    private fun tokens(file: PsiFile): List<CommentToken> =
        file.viewProvider.allFiles
            .flatMap { PsiTreeUtil.collectElementsOfType(it, PsiComment::class.java) }
            .distinctBy { it.textRange }
            .sortedBy { it.textRange.startOffset }
            .mapNotNull(::token)

    private fun token(comment: PsiComment): CommentToken? {
        if (comment is PsiDocCommentBase) return null
        val text = comment.text
        val start = comment.textRange.startOffset
        val span = Span(start, comment.textRange.endOffset)
        val commenter = LanguageCommenters.INSTANCE.forLanguage(comment.language)

        val linePrefixes = commenter?.lineCommentPrefixes ?: FALLBACK_LINE_PREFIXES
        // Shell reports `#` as its doc comment prefix too, which would make every comment a doc comment.
        val docPrefix = (commenter as? CodeDocumentationAwareCommenter)?.documentationCommentPrefix?.takeIf { it !in linePrefixes }
        if (docPrefix != null && text.startsWith(docPrefix) && text != "/**/") return null

        val linePrefix = linePrefixes.firstOrNull { text.startsWith(it) }
        if (linePrefix != null && '\n' !in text.trimEnd()) {
            val body = text.substring(linePrefix.length).trimEnd()
            return CommentToken(CommentKind.LINE, span, start + linePrefix.length + body.length, body)
        }

        val blockPrefix = commenter?.blockCommentPrefix ?: "/*"
        val blockSuffix = commenter?.blockCommentSuffix ?: "*/"
        if (text.length < blockPrefix.length + blockSuffix.length || !text.startsWith(blockPrefix) || !text.endsWith(blockSuffix)) return null
        val bodyEnd = span.end - blockSuffix.length
        return CommentToken(CommentKind.BLOCK, span, bodyEnd, text.substring(blockPrefix.length, text.length - blockSuffix.length), jsxWrapper(comment) ?: span)
    }

    /**
     * `{/* … */}` in JSX is an embedded expression holding nothing but the comment, so stripping the comment
     * alone would leave empty braces. The type is matched by name to avoid depending on the JavaScript plugin,
     * and because a code block `{ /* … */ }` has the same text shape but must keep its braces.
     */
    private fun jsxWrapper(comment: PsiComment): Span? {
        val parent: PsiElement = comment.parent ?: return null
        if (!parent.node.elementType.toString().contains("EMBEDDED", ignoreCase = true)) return null
        val parentText = parent.text
        if (!parentText.startsWith("{") || !parentText.endsWith("}")) return null
        val onlyComment = parent.node.getChildren(null).all {
            it.psi === comment || it.psi is PsiWhiteSpace || it.text == "{" || it.text == "}"
        }
        return if (onlyComment) Span(parent.textRange.startOffset, parent.textRange.endOffset) else null
    }

    private val FALLBACK_LINE_PREFIXES = listOf("//", "#")
}
