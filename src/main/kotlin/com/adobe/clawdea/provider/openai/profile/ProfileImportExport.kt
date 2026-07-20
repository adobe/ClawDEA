package com.adobe.clawdea.provider.openai.profile

import com.google.gson.Gson

data class ConfiguredProfileExport(
    val profile: OpenAiCompatibleProfile,
    val configuredValues: Map<String, String>,
    val credentialRef: String,
)

object ProfileImportExport {
    private val gson = Gson()

    fun exportTemplate(profile: OpenAiCompatibleProfile): String = gson.toJson(profile)

    fun exportConfigured(
        profile: OpenAiCompatibleProfile,
        values: Map<String, String>,
    ): String {
        val secretBearingIds = buildSet {
            addAll(profile.credentialFlow.inputs.map { it.id })
            addAll(profile.credentialFlow.steps.flatMap { step -> step.extracts.map { it.name } })
        }
        return gson.toJson(
            ConfiguredProfileExport(
                profile = profile,
                configuredValues = values.filterKeys { key ->
                    key !in secretBearingIds &&
                        profile.settings.any { setting ->
                            setting.id == key && setting.environmentVariable == null
                        }
                },
                credentialRef = "passwordsafe:openai-compatible/${profile.id}",
            ),
        )
    }
}
