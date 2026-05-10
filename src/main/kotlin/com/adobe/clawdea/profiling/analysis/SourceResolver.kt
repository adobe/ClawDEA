package com.adobe.clawdea.profiling.analysis

import com.adobe.clawdea.profiling.model.Frame
import com.adobe.clawdea.util.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

object SourceResolver {

    fun buildSourceLocationFromFrame(
        frame: Frame,
        filePath: String?,
        versionHint: String? = null,
    ): SourceLocation {
        return SourceLocation(
            fqn = frame.className,
            methodName = frame.methodName,
            filePath = filePath,
            startLine = frame.lineNumber,
            endLine = frame.lineNumber,
            inProject = filePath != null,
            versionHint = versionHint,
        )
    }

    fun resolveInProject(frame: Frame, project: Project): SourceLocation? {
        return runReadAction {
            val facade = JavaPsiFacade.getInstance(project)
            val scope = GlobalSearchScope.projectScope(project)
            val psiClass = facade.findClass(frame.className, scope) ?: return@runReadAction null
            val file = psiClass.containingFile?.virtualFile ?: return@runReadAction null
            val projectBase = project.basePath ?: return@runReadAction null
            val relativePath = file.path.removePrefix(projectBase).removePrefix("/")
            SourceLocation(
                fqn = frame.className,
                methodName = frame.methodName,
                filePath = relativePath,
                startLine = frame.lineNumber,
                endLine = frame.lineNumber,
                inProject = true,
                versionHint = null,
            )
        }
    }
}
