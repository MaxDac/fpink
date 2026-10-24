package com.fpink.capture.acceptance

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.R
import com.fpink.capture.ui.settings.SettingsScreen
import com.fpink.capture.ui.settings.SettingsViewModel
import com.fpink.capture.ui.theme.ThemeUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TipLinkAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var storage: AcceptanceStorage
    private lateinit var settings: SettingsViewModel
    private val models = ViewModelStore()
    private val launches = mutableListOf<Intent>()
    private var launchFailure: RuntimeException? = null
    private var backs = 0

    @Before fun setUp() {
        storage = AcceptanceStorage()
        compose.runOnUiThread {
            settings = SettingsViewModel(storage.settings)
            models.put("settings", settings)
        }
        val browserContext = object : ContextWrapper(compose.activity) {
            override fun startActivity(intent: Intent) {
                launches += intent
                launchFailure?.let { throw it }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides browserContext) {
                MaterialTheme {
                    SettingsScreen(
                        onBack = { backs++ },
                        appearance = ThemeUiState(loading = false),
                        onThemeSelected = {},
                        viewModel = settings,
                    )
                }
            }
        }
        compose.waitUntil(10_000) { !settings.uiState.value.loading }
    }

    @After fun tearDown() {
        compose.runOnUiThread { models.clear() }
        storage.close()
    }

    @Test fun tipIsOptionalAccessibleAndLaunchesOnlyTheExactExternalUrlOnTap() {
        compose.onNodeWithText(text(R.string.leave_a_tip)).performScrollTo()
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithText(text(R.string.tip_help)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(launches.isEmpty())
            assertFalse(settings.uiState.value.zettelkastenEnabled)
        }
        repeat(2) {
            compose.onNodeWithText(text(R.string.leave_a_tip)).performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(2, launches.size)
            launches.forEach { intent ->
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals("https://ko-fi.com/maxdac", intent.dataString)
                assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
                assertNull(intent.extras)
                assertNull(intent.clipData)
                assertNull(intent.component)
                assertNull(intent.`package`)
            }
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun missingBrowserShowsAnErrorAndSuccessfulRetryClearsIt() {
        assertFailureAndRetry(ActivityNotFoundException("test browser missing"), R.string.tip_browser_unavailable)
    }

    @Test fun blockedBrowserShowsAnErrorAndSuccessfulRetryClearsIt() {
        assertFailureAndRetry(SecurityException("test browser blocked"), R.string.tip_browser_blocked)
    }

    private fun assertFailureAndRetry(failure: RuntimeException, message: Int) {
        compose.runOnIdle { launchFailure = failure }
        compose.onNodeWithText(text(R.string.leave_a_tip)).performScrollTo().performClick()
        compose.onNodeWithText(text(message)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { launchFailure = null }
        compose.onNodeWithText(text(R.string.leave_a_tip)).performScrollTo().performClick()
        compose.onNodeWithText(text(message)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, launches.size) }
    }

    private fun text(resource: Int): String = compose.activity.getString(resource)
}
