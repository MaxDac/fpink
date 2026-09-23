package com.fpink.capture.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.ServiceLoader

/**
 * Extension point for the Settings screen, populated only in the `full` flavor by the private
 * app-overlay (never present in FOSS/F-Droid builds; see docs/ARCHITECTURE.md's "Public/private
 * flavor split"). When no implementation is discovered, [SettingsScreen] renders exactly as it
 * does today: Appearance and Zettelkasten only, with recognition always PaddleOCR.
 *
 * Mirrors the [com.fpink.core.ai.RecognitionProviderPlugin] seam: discovered via
 * [ServiceLoader], so this module never depends on private code, even by interface name.
 */
interface SettingsExtension {
    /** Rendered below the built-in settings sections, in its own scrollable column slot. */
    @Composable
    fun Content(modifier: Modifier)
}

/** Discovers a [SettingsExtension] bundled into the current build, if any. */
object SettingsExtensionRegistry {
    fun find(): SettingsExtension? =
        ServiceLoader.load(SettingsExtension::class.java).firstOrNull()
}
