package io.github.jacekgajek.koog.graph.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import io.github.jacekgajek.koog.graph.parser.StrategyParser
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/**
 * Locates every `strategy { }` call in the project. The lookup is index-backed: the
 * platform's word index narrows the search to the handful of files that actually
 * mention the `strategy` identifier in code, and only those files are walked. No
 * symbol resolution happens, so it's cheap and works in both K1 and K2 modes.
 */
object StrategyIndex {

    /** A strategy found in the project, with enough state to render and locate it. */
    data class FoundStrategy(
        val name: String,
        val pointer: SmartPsiElementPointer<KtCallExpression>,
        val file: VirtualFile,
        /** Project-relative `path:line` for display. */
        val location: String,
    )

    /**
     * Find all strategies in the project. Must be called inside a read action and in
     * smart mode (the word index isn't available during indexing).
     */
    fun findAll(project: Project): List<FoundStrategy> {
        val scope = GlobalSearchScope.getScopeRestrictedByFileTypes(
            GlobalSearchScope.projectScope(project), KotlinFileType.INSTANCE,
        )
        val parser = StrategyParser()
        val spm = SmartPointerManager.getInstance(project)
        val pdm = PsiDocumentManager.getInstance(project)
        val base = project.basePath

        val result = mutableListOf<FoundStrategy>()
        PsiSearchHelper.getInstance(project).processAllFilesWithWord("strategy", scope, { psiFile ->
            val ktFile = psiFile as? KtFile ?: return@processAllFilesWithWord true
            val vf = ktFile.virtualFile ?: return@processAllFilesWithWord true
            val doc = pdm.getDocument(ktFile)
            PsiTreeUtil.collectElementsOfType(ktFile, KtCallExpression::class.java).forEach { call ->
                if (!parser.isRenderableStrategyCall(call)) return@forEach
                val name = parser.strategyName(call)?.takeIf { it.isNotBlank() } ?: "<unnamed>"
                val line = doc?.let { it.getLineNumber(call.textOffset) + 1 } ?: 0
                val rel = relativePath(base, vf)
                result += FoundStrategy(
                    name = name,
                    pointer = spm.createSmartPsiElementPointer(call),
                    file = vf,
                    location = if (line > 0) "$rel:$line" else rel,
                )
            }
            true
        }, true)

        return result.sortedWith(compareBy({ it.name.lowercase() }, { it.location }))
    }

    private fun relativePath(base: String?, vf: VirtualFile): String {
        if (base != null) {
            val baseVf = vf.fileSystem.findFileByPath(base)
            if (baseVf != null) {
                VfsUtilCore.getRelativePath(vf, baseVf)?.let { return it }
            }
        }
        return vf.name
    }
}
