package com.fpink.capture.acceptance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.R
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.RecognitionCoordinator
import com.fpink.capture.navigation.NotesListDestination
import com.fpink.capture.navigation.Routes
import com.fpink.capture.ui.capture.CaptureScreen
import com.fpink.capture.ui.capture.CaptureViewModel
import com.fpink.capture.ui.notes.NotesListScreen
import com.fpink.capture.ui.notes.NotesListViewModel
import com.fpink.capture.ui.notes.SourceImportViewModel
import com.fpink.core.ai.DefaultNoteProcessor
import com.fpink.core.model.Note
import com.fpink.core.storage.FileStore
import com.fpink.core.storage.NoteRepository
import java.io.IOException
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesListActionsAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var storage: AcceptanceStorage
    private lateinit var notes: NotesListViewModel
    private lateinit var repository: NoteRepository
    private lateinit var source: SourceImportViewModel
    private val models = ViewModelStore()
    private var failListing = false
    private var listingGate: CompletableDeferred<Unit>? = null
    private var dark by mutableStateOf(false)
    private var compact by mutableStateOf(false)
    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var result by mutableStateOf<String?>(null)
    private var sourceReadyCount = 0
    private var cameras = 0
    private val launches = mutableListOf<Intent>()
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
            ) {
                launches += contract.createIntent(compose.activity, input)
            }
        }
    }

    @Before fun setUp() {
        storage = AcceptanceStorage()
        val files = AndroidFileStore(storage.context)
        repository = NoteRepository(object : FileStore by files {
            override suspend fun list(directory: String): Result<List<String>> {
                listingGate?.await()
                return if (failListing) Result.failure(IOException("fixture listing failure")) else files.list(directory)
            }
        })
        compose.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            notes = NotesListViewModel(repository)
            source = SourceImportViewModel(storage.images)
            models.put("notes", notes)
        }
    }

    @After fun cleanUp() {
        listingGate?.complete(Unit)
        compose.runOnUiThread { models.clear() }
        storage.close()
    }

    private fun showNotes() {
        compose.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides direction,
                LocalActivityResultRegistryOwner provides registryOwner,
            ) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Box(
                        (if (compact) Modifier.fillMaxWidth().height(280.dp) else Modifier.fillMaxSize())
                            .testTag("notesBounds"),
                    ) {
                        NotesListScreen(
                            onSourceReady = { sourceReadyCount++ }, onCameraClick = { cameras++ },
                            onNoteClick = {}, onSettingsClick = {},
                            resultMessage = result, onResultShown = { result = null },
                            viewModel = notes, sourceViewModel = source,
                        )
                    }
                }
            }
        }
        compose.waitUntil(10_000) { !notes.uiState.value.isLoading }
    }

    @Test fun selectedNotesCanBeSharedAsJsonOrPlainTextWithoutClearingSelection() {
        seedNotes()
        showNotes()
        lateinit var selected: List<Note>
        compose.runOnIdle {
            selected = notes.uiState.value.notes.take(2)
            selected.forEach { notes.select(it.id) }
        }

        compose.onNodeWithContentDescription("Share selected")
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("JSON").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val shared = sharedIntent()
            assertEquals("application/json", shared.type)
            val payload = shared.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            assertTrue(payload.indexOf(selected[0].id) < payload.indexOf(selected[1].id))
            assertTrue(selected.all { it.text in payload })
            assertFalse(payload.contains("\"imagePath\""))
            assertEquals(selected.map { it.id }.toSet(), notes.uiState.value.selectedIds)
        }

        compose.onNodeWithContentDescription("Share selected").performClick()
        compose.onNodeWithText("Notes").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val shared = sharedIntent()
            assertEquals("text/plain", shared.type)
            assertEquals(selected.joinToString("\n\n") { it.text }, shared.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(selected.map { it.id }.toSet(), notes.uiState.value.selectedIds)
        }
    }

    @Test fun emptyAndBottomAddOpenTheSameSourceMenuAndCameraHasItsOwnAction() {
        showNotes()
        val addButtons = compose.onAllNodesWithContentDescription("Add notes")
        assertEquals(2, addButtons.fetchSemanticsNodes().size)
        addButtons[0].assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertSourceMenuOpen()
        compose.onNodeWithTag("sourceMenuScrim").performClick()
        assertSourceMenuClosed()
        addButtons[1].assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertSourceMenuOpen()
        compose.onNodeWithTag("sourceMenuScrim").performClick()
        assertSourceMenuClosed()
        compose.onNodeWithContentDescription("Take photo")
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(0, sourceReadyCount)
            assertEquals(1, cameras)
        }
        assertBottomActions()
    }

    @Test fun actionsReserveListAndSnackbarClearanceInBothThemesCompactHeightAndRtl() {
        seedNotes()
        showNotes()
        for (isDark in listOf(false, true)) {
            for (isCompact in listOf(false, true)) {
                compose.runOnIdle { dark = isDark; compact = isCompact }
                assertBottomActions()
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
                compose.onNodeWithText("Fixture note 29").assertIsDisplayed()
                val last = compose.onNodeWithText("Fixture note 29").fetchSemanticsNode().boundsInRoot
                val camera = compose.onNodeWithContentDescription("Take photo").fetchSemanticsNode().boundsInRoot
                assertTrue("Last note must not be hidden behind either action", last.bottom <= camera.top)
            }
        }
        compose.runOnIdle { compact = false; result = "Fixture notes created" }
        compose.onNodeWithText("Fixture notes created").assertIsDisplayed()
        val snackbar = compose.onNodeWithText("Fixture notes created").fetchSemanticsNode().boundsInRoot
        val camera = compose.onNodeWithContentDescription("Take photo").fetchSemanticsNode().boundsInRoot
        assertTrue("Snackbar must remain above the actions", snackbar.bottom <= camera.top)
        compose.runOnIdle { direction = LayoutDirection.Rtl }
        assertBottomActions()
    }

    @Test fun loadingAndErrorsKeepBothBottomActionsAvailable() {
        showNotes()
        compose.runOnIdle {
            listingGate = CompletableDeferred()
            notes.refresh()
        }
        compose.waitUntil { notes.uiState.value.isLoading }
        assertBottomActions()
        compose.runOnIdle {
            failListing = true
            listingGate?.complete(Unit)
            listingGate = null
        }
        compose.waitUntil(10_000) { notes.uiState.value.error != null && !notes.uiState.value.isLoading }
        compose.onNodeWithText(
            compose.activity.getString(R.string.notes_load_error, "fixture listing failure"),
        ).assertIsDisplayed()
        assertBottomActions()
        compose.onNodeWithContentDescription("Add notes").performClick()
        assertSourceMenuOpen()
        compose.onNodeWithTag("sourceMenuScrim").performClick()
        compose.onNodeWithContentDescription("Take photo").performClick()
        compose.runOnIdle { assertEquals(0, sourceReadyCount); assertEquals(1, cameras) }
    }

    @Test fun asymmetricNavigationAndGestureInsetsKeepBothActionsInsideSafeBounds() {
        seedNotes()
        showNotes()
        val density = compose.activity.resources.displayMetrics.density
        val left = (36 * density).toInt()
        val right = (48 * density).toInt()
        val bottom = (32 * density).toInt()
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder(
                ViewCompat.getRootWindowInsets(compose.activity.window.decorView) ?: WindowInsetsCompat.CONSUMED,
            )
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(left, 0, right, bottom))
                .setInsets(WindowInsetsCompat.Type.systemGestures(), Insets.of(left, 0, right, bottom))
                .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, bottom))
                .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
        val root = compose.onNodeWithTag("notesBounds").fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithContentDescription("Add notes").fetchSemanticsNode().boundsInRoot
        val camera = compose.onNodeWithContentDescription("Take photo").fetchSemanticsNode().boundsInRoot
        assertTrue("Start action clears side inset", add.left >= root.left + left)
        assertTrue("End action clears side inset", camera.right <= root.right - right)
        assertTrue("Actions clear bottom navigation/gestures", camera.bottom <= root.bottom - bottom)
    }

    @Test fun productionCameraCallbackNavigatesOnceUnderRapidTapsWhileAddOpensALocalMenu() {
        assumeTrue("Unchanged user permissions: this case intercepts requests only if permission is not granted",
            ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        assumeTrue(compose.activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        lateinit var nav: NavHostController
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
                MaterialTheme {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = Routes.NOTES_LIST) {
                        composable(Routes.NOTES_LIST) { entry ->
                            NotesListDestination(nav, entry, notes, source)
                        }
                        composable(
                            Routes.CAPTURE_DESTINATION,
                            arguments = listOf(
                                navArgument(Routes.CAMERA_ENTRY) { type = NavType.BoolType; defaultValue = false },
                                navArgument(Routes.SOURCE_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
                            ),
                        ) { entry ->
                            val capture = remember(entry) {
                                val coordinator = RecognitionCoordinator(
                                    storage.images, storage.settings, repository, DefaultNoteProcessor(),
                                    { error("Navigation tests must never run recognition") },
                                )
                                CaptureViewModel(storage.images, coordinator, storage.settings, entry.savedStateHandle).also {
                                    models.put(entry.id, it)
                                }
                            }
                            CaptureScreen(
                                onImageCaptured = { error("Tests must not confirm recognition") },
                                onBack = { nav.popBackStack() }, onSettings = {},
                                startWithCamera = entry.arguments?.getBoolean(Routes.CAMERA_ENTRY) == true,
                                viewModel = capture,
                            )
                        }
                    }
                }
            }
        }
        waitForNotes(nav)
        // Exercise both real production Add entry points (empty state and bottom action): each
        // opens the local Gallery/File menu without ever navigating away from the notes list.
        for (index in 0..1) {
            compose.onAllNodesWithContentDescription("Add notes")[index].performClick()
            assertSourceMenuOpen()
            compose.runOnIdle {
                assertEquals(Routes.NOTES_LIST, nav.currentBackStackEntry?.destination?.route)
                assertTrue(launches.isEmpty())
            }
            compose.onNodeWithTag("sourceMenuScrim").performClick()
            assertSourceMenuClosed()
        }
        val addAction = compose.onAllNodesWithContentDescription("Add notes")[1]
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val cameraAction = compose.onNodeWithContentDescription("Take photo")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            cameraAction()
            cameraAction()
            addAction()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(true, nav.currentBackStackEntry?.arguments?.getBoolean(Routes.CAMERA_ENTRY))
            assertEquals(1, launches.size)
            assertEquals(Routes.NOTES_LIST, nav.previousBackStackEntry?.destination?.route)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        waitForNotes(nav)
        compose.onNodeWithContentDescription("Take photo").performClick()
        compose.runOnIdle { assertEquals(2, launches.size) }
        compose.onNodeWithContentDescription("Back").performClick()
        waitForNotes(nav)
        compose.runOnIdle { assertFalse(nav.popBackStack()) }
    }

    private fun assertSourceMenuOpen() {
        compose.onNodeWithText("Gallery").assertIsDisplayed()
        compose.onNodeWithText("File").assertIsDisplayed()
    }

    private fun assertSourceMenuClosed() {
        compose.onNodeWithText("Gallery").assertDoesNotExist()
        compose.onNodeWithText("File").assertDoesNotExist()
    }

    @Suppress("DEPRECATION")
    private fun sharedIntent(): Intent {
        val chooser = launches.removeLast()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT))
    }

    private fun waitForNotes(nav: NavHostController) {
        compose.waitUntil(10_000) {
            nav.currentBackStackEntry?.destination?.route == Routes.NOTES_LIST &&
                nav.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED &&
                !notes.uiState.value.isLoading
        }
        compose.waitForIdle()
    }

    private fun seedNotes() {
        runBlocking {
            repeat(30) { index ->
                repository.save(Note(
                    id = "fixture-$index", text = "Fixture note $index", imagePath = "images/fixture.png",
                    capturedAt = Instant.fromEpochMilliseconds((30 - index).toLong()),
                )).getOrThrow()
            }
        }
        compose.runOnUiThread { notes.refresh() }
    }

    private fun assertBottomActions() {
        val root = compose.onNodeWithTag("notesBounds").fetchSemanticsNode().boundsInRoot
        val adds = compose.onAllNodesWithContentDescription("Add notes").fetchSemanticsNodes()
        val add = adds.maxBy { it.boundsInRoot.top }.boundsInRoot
        val camera = compose.onNodeWithContentDescription("Take photo")
            .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(add.left >= root.left && add.right <= root.right)
        assertTrue(camera.left >= root.left && camera.right <= root.right)
        assertTrue(add.bottom <= root.bottom && camera.bottom <= root.bottom)
        assertEquals(add.top, camera.top, 1f)
        if (direction == LayoutDirection.Ltr) assertTrue(add.right < camera.left)
        else assertTrue(camera.right < add.left)
    }
}
