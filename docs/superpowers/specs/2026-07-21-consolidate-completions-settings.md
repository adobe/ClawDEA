# Consolidate Completions Settings into Advanced Tab

**Date:** 2026-07-21
**Status:** Design approved

## Problem

After the Completions model selection moved to the Roles tab, a redundant `completionsModelCombo` dropdown (Sonnet / Haiku) remains in `ProvidersTab`. Additionally, the other completions behavioral settings (`completionsEnabled`, `completionsDebounceMs`, `completionsManualOnly`) are provider-scoped in the UI even though they apply globally — they're not specific to whichever provider is currently selected in the dropdown.

## Design

### What changes

**Remove from `ProvidersTab.kt`:**
- `completionsEnabledCheckbox`
- `completionsModelCombo`
- `completionsDebounceField`
- `completionsManualOnlyCheckbox`
- `COMPLETION_MODELS` and `COMPLETION_MODEL_KEYS` arrays
- All `loadFrom()` / `applyTo()` / `isModifiedFrom()` wiring for these fields
- The corresponding rows in the FormBuilder component

**Add to `AdvancedTab.kt`:**
- Keep existing: `completionsEnabledCheckbox`, `completionsDebounceField`, `completionsManualOnlyCheckbox`
- Add: `completionsModelCombo` (same model arrays, same dropdown behavior)
- The model combo will sit between the "Enable inline completions" checkbox and the debounce field for logical grouping
- Wire `loadFrom()`, `applyTo()`, and `isModifiedFrom()` for the new field

**No changes to `ClawDEASettings.State`:**
- All four fields (`completionsEnabled`, `completionsModel`, `completionsDebounceMs`, `completionsManualOnly`) already exist as flat fields on the state object.
- We're only moving the UI surface, not the storage schema.
- No migration code needed.

### Layout in Advanced Tab

```
[ ] Enable inline completions
[Completions model: | Sonnet ▼ ]
Completions debounce (ms): [300]
[ ] Only request completions on hotkey (Trigger Inline Completion, default Alt+\)
```

The model combo will be labeled "Completions model:" with the same Sonnet/Haiku options.

### Backwards Compatibility

Existing user configs work unchanged — the `completionsModel` field already exists in `ClawDEASettings.State`. The only visible change is where the UI lives.

### Testing

- `AdvancedTabTest`: verify `completionsModelCombo` loads from state, applies to state, and dirty-checks correctly.
- `ProvidersTabTest`: remove assertions around the removed completions fields.
- Integration: verify `ClawDEACompletionProvider` still reads `state.completionsModel` and `state.completionsEnabled` correctly (no code path changes, just UI location).
