package com.adobe.clawdea.chat

/** What a settings apply should do to an already-open chat tab. */
internal enum class SettingsApplyAction {
    /** Leave the tab untouched (streaming, or no live bridge to refresh). */
    NONE,

    /**
     * Restart the tab's bridge in place to pick up non-provider settings (tool-approval mode,
     * toggles, CLI path/args, wiki-librarian model, …). The tab keeps its own per-tab provider and
     * model — a settings apply never migrates an open tab to a different provider/backend.
     */
    RESTART_IN_PLACE,
}

/**
 * Decide how a settings apply affects an already-open chat tab.
 *
 * A settings apply must NOT change the tab's provider or backend: the tab keeps the per-tab
 * [com.adobe.clawdea.provider.AgentSelection] it was built with, and only NEW chats adopt a changed
 * global default. So the only effect on a live, idle tab is an in-place restart to refresh
 * non-provider settings; a streaming or not-running tab is left alone.
 */
internal fun settingsApplyAction(bridgeRunning: Boolean, isStreaming: Boolean): SettingsApplyAction =
    if (bridgeRunning && !isStreaming) SettingsApplyAction.RESTART_IN_PLACE else SettingsApplyAction.NONE
