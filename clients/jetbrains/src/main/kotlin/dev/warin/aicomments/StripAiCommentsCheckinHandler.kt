package dev.warin.aicomments

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.ui.BooleanCommitOption
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Service(Service.Level.APP)
@State(name = "AiCommentsSettings", storages = [Storage("aiComments.xml")])
class AiCommentsSettings : SimplePersistentStateComponent<AiCommentsSettings.SettingsState>(SettingsState()) {
    class SettingsState : BaseState() {
        var stripOnCommit by property(true)
    }

    var stripOnCommit: Boolean
        get() = state.stripOnCommit
        set(value) {
            state.stripOnCommit = value
        }
}

class StripAiCommentsCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler = StripAiCommentsCheckinHandler(panel.project)
}

/** Runs in the modification phase, registered first so that reformat-before-commit tidies up after it. */
class StripAiCommentsCheckinHandler(private val project: Project) : CheckinHandler(), CommitCheck {
    private val settings get() = service<AiCommentsSettings>()

    override fun getExecutionOrder() = CommitCheck.ExecutionOrder.MODIFICATION

    override fun isEnabled() = settings.stripOnCommit

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent =
        BooleanCommitOption.create(project, this, false, "Strip AI comments", settings::stripOnCommit)

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        val files = commitInfo.committedChanges.mapNotNull { it.virtualFile }.distinct()
        withContext(Dispatchers.EDT) {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            files.forEach(::strip)
        }
        return null
    }

    /** Parses inside the write action, so the offsets can't go stale before the deletions are applied. */
    private fun strip(file: VirtualFile) {
        if (!file.isValid || !file.isWritable || file.fileType.isBinary) return
        val documents = FileDocumentManager.getInstance()
        val document = documents.getDocument(file) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Strip AI Comments", null, {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@runWriteCommandAction
            val deletions = AiCommentStripper.deletions(document.charsSequence, AiCommentFinder.find(psiFile).comments)
            if (deletions.isEmpty()) return@runWriteCommandAction
            deletions.forEach { document.deleteString(it.start, it.end) }
            documents.saveDocument(document)
        })
    }
}
