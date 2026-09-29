package dev.warin.aicomments

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AiCommentDiffHighlighterTest : BasePlatformTestCase() {
    fun `test paints AI comments above the diff in diff editors`() {
        // Given a file with an AI comment shown in a diff editor
        myFixture.configureByText("a.ts", "a();\nb(); // — Was a() —\n")
        val diff = EditorFactory.getInstance().createEditor(myFixture.editor.document, project, EditorKind.DIFF)
        try {
            // When the file is highlighted
            myFixture.doHighlighting()

            // Then the diff editor gets its own copy of the AI comment above the diff layers, and loses it with the comment
            val copies = { diff.markupModel.allHighlighters.filter { it.layer == HighlighterLayer.SELECTION - 1 && it.textAttributesKey == AiCommentColors.AI_COMMENT } }
            assertEquals("// — Was a() —", copies().single().textRange.substring(diff.document.text))
            assertEmpty(myFixture.editor.markupModel.allHighlighters.filter { it.textAttributesKey == AiCommentColors.AI_COMMENT })

            WriteCommandAction.runWriteCommandAction(project) { diff.document.setText("a();\nb();\n") }
            myFixture.doHighlighting()
            assertEmpty(copies())
        } finally {
            EditorFactory.getInstance().releaseEditor(diff)
        }
    }
}
