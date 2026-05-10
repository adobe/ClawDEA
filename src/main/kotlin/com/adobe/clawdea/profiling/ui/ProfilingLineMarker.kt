package com.adobe.clawdea.profiling.ui

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner

class ProfilingLineMarker : LineMarkerProvider {

    private val testAnnotations = setOf(
        "org.junit.Test",
        "org.junit.jupiter.api.Test",
        "org.testng.annotations.Test",
    )

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        if (!hasTestAnnotation(method)) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.RunConfigurations.TestState.Run,
            { "Profile this test with ClawDEA" },
            { _, _ -> },
            GutterIconRenderer.Alignment.LEFT,
            { "Profile test" },
        )
    }

    private fun hasTestAnnotation(method: PsiMethod): Boolean {
        val modList = (method as? PsiModifierListOwner)?.modifierList ?: return false
        return modList.annotations.any { annotation ->
            annotation.qualifiedName in testAnnotations
        }
    }
}
