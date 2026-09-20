package com.fpink.capture.acceptance

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.data.ThemeMode
import com.fpink.capture.ui.settings.SettingsScreen
import com.fpink.capture.ui.settings.SettingsViewModel
import com.fpink.capture.ui.theme.FPInkTheme
import com.fpink.capture.ui.theme.ThemeViewModel
import com.fpink.core.ai.RecognitionProviderId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var storage: AcceptanceStorage
    private lateinit var theme: ThemeViewModel
    private lateinit var settings: SettingsViewModel
    private val models = ViewModelStore()
    private var backgroundLuminance = 0f
    private var backs = 0
    private var systemDark by mutableStateOf(false)

    @Before fun setUp() {
        storage = AcceptanceStorage()
        compose.runOnUiThread {
            theme = ThemeViewModel(storage.settings)
            settings = SettingsViewModel(storage.settings, { error("Must never contact Azure") }, { Result.success(Unit) })
            models.put("theme", theme)
            models.put("settings", settings)
        }
        compose.setContent {
            val appearance by theme.uiState.collectAsStateWithLifecycle()
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                FPInkTheme(appearance.mode, compose.activity) {
                    backgroundLuminance = MaterialTheme.colorScheme.background.luminance()
                    SettingsScreen({ backs++ }, appearance, theme::select, settings)
                }
            }
        }
        compose.waitUntil(10_000) { !theme.uiState.value.loading && !settings.uiState.value.loading }
    }

    @After fun tearDown() {
        compose.runOnUiThread { models.clear() }
        storage.close()
    }

    @Test fun appearanceAppliesImmediatelyWithoutSavingOrReplacingPendingRecognitionEdits() {
        compose.onNodeWithText("System (default)").assertIsSelected()
        compose.runOnIdle {
            settings.selectProvider(RecognitionProviderId.AZURE)
            settings.onEndpointChange("https://unsaved.cognitiveservices.azure.com")
            settings.onApiKeyChange("unsaved-local-fixture")
        }
        compose.onNodeWithText("Dark").performClick().assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertTrue(backgroundLuminance < 0.1f) }
        compose.onNodeWithText("Light").performClick().assertIsSelected()
        compose.runOnIdle { assertTrue(backgroundLuminance > 0.9f) }
        compose.onNodeWithText("Dark").performClick()
        compose.waitUntil(10_000) { !theme.uiState.value.saving }
        runBlocking {
            assertEquals(ThemeMode.DARK, storage.settings.themeMode.first())
            assertEquals(RecognitionProviderId.PADDLE, storage.settings.recognitionSettings.first().provider)
            assertEquals("", storage.settings.recognitionSettings.first().azure.endpoint)
        }
        compose.runOnIdle {
            assertEquals(RecognitionProviderId.AZURE, settings.uiState.value.provider)
            assertEquals("https://unsaved.cognitiveservices.azure.com", settings.uiState.value.endpoint)
            assertEquals("unsaved-local-fixture", settings.uiState.value.replacementKey)
        }
    }

    @Test fun backActionHasAccessibleNameTouchTargetAndOriginalHandler() {
        compose.onNodeWithContentDescription("Back")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun systemTracksLiveConfigurationManualOverridesIgnoreItAndBarsMatchApp() {
        assertAppearance(dark = false)
        compose.runOnIdle { systemDark = true }
        assertAppearance(dark = true)
        compose.onNodeWithText("Light").performClick()
        assertAppearance(dark = false)
        compose.runOnIdle { systemDark = false }
        assertAppearance(dark = false)
        compose.onNodeWithText("Dark").performClick()
        assertAppearance(dark = true)
        compose.runOnIdle { systemDark = true }
        assertAppearance(dark = true)
        compose.runOnIdle { systemDark = false }
        assertAppearance(dark = true)
        compose.onNodeWithText("System (default)").performClick().assertIsSelected()
        assertAppearance(dark = false)
        compose.runOnIdle { systemDark = true }
        assertAppearance(dark = true)
        compose.waitUntil(10_000) { !theme.uiState.value.saving }
    }

    private fun assertAppearance(dark: Boolean) {
        compose.runOnIdle {
            assertTrue(if (dark) backgroundLuminance < 0.1f else backgroundLuminance > 0.9f)
            val controller = WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            assertEquals(!dark, controller.isAppearanceLightStatusBars)
            assertEquals(!dark, controller.isAppearanceLightNavigationBars)
        }
    }
}
