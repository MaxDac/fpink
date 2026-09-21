package com.fpink.capture.acceptance

import android.graphics.Bitmap
import android.graphics.Color
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fpink.capture.R
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.data.ThemeMode
import com.fpink.capture.ui.notes.NotesListScreen
import com.fpink.capture.ui.notes.NotesListViewModel
import com.fpink.capture.ui.notes.SourceImportViewModel
import com.fpink.capture.ui.theme.FPInkTheme
import com.fpink.core.model.InkColorOrigin
import com.fpink.core.model.Note
import com.fpink.core.storage.FileStore
import com.fpink.core.storage.NoteRepository
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotesListAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var storage: AcceptanceStorage
    private lateinit var disk: AndroidFileStore
    private lateinit var viewModel: NotesListViewModel
    private val models = ViewModelStore()
    private val openedNotes = mutableListOf<String>()
    private var sourceReadyCount = 0
    private var cameraClicks = 0
    private var settingsClicks = 0
    private var navigationBacks = 0
    private var themeMode by mutableStateOf(ThemeMode.LIGHT)
    private var backgroundLuminance = 0f

    @Before fun setUp() {
        storage = AcceptanceStorage()
        disk = AndroidFileStore(storage.context)
    }

    @After fun tearDown() {
        try {
            compose.runOnUiThread { models.clear() }
        } finally {
            storage.close()
        }
    }

    @Test fun normalTouchOpensOnlyThatNoteAndKeepsCaptureAndSettingsAvailable() {
        val notes = seedLegacyNotes(3)
        showNotes()

        row(notes[0]).assert(role(Role.Button)).assertMinimumTouchTarget()
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(notes[0].id), openedNotes)
            assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
        }
        compose.onNodeWithText("Second line of legacy-0").assertDoesNotExist()
        icon(R.string.settings).assertMinimumTouchTarget().performClick()
        icon(R.string.add_notes).assertMinimumTouchTarget().performClick()
        compose.onNodeWithText("Gallery").assertIsDisplayed()
        compose.onNodeWithText("File").assertIsDisplayed()
        compose.onNodeWithTag("sourceMenuScrim").performClick()
        compose.onNodeWithText("Gallery").assertDoesNotExist()
        icon(R.string.take_photo).assertMinimumTouchTarget().performClick()
        compose.runOnIdle {
            assertEquals(1, settingsClicks)
            assertEquals(0, sourceReadyCount)
            assertEquals(1, cameraClicks)
        }
        assertNormalToolbar()
    }

    @Test fun touchLongPressEntersSelectionAndRepeatingItDoesNotToggleOrNavigate() {
        val notes = seedLegacyNotes(3)
        showNotes()

        row(notes[0]).performTouchInput { longClick() }
        assertSelection(notes[0])
        row(notes[0]).assertChecked(ToggleableState.On).assertMinimumTouchTarget()
        row(notes[1]).assertChecked(ToggleableState.Off)
        selectAll().assertChecked(ToggleableState.Indeterminate).assertHeightIsAtLeast(48.dp)
        assertSelectionToolbar(1)

        row(notes[0]).performTouchInput { longClick() }
        assertSelection(notes[0])
        row(notes[1]).performTouchInput { longClick() }
        assertSelection(notes[0], notes[1])
        assertSelectionToolbar(2)
        compose.runOnIdle { assertTrue(openedNotes.isEmpty()) }
    }

    @Test fun rowAndVisualCheckboxTouchesEachToggleExactlyOnceAndDeselectingLastExits() {
        val notes = seedLegacyNotes(3)
        showNotes()
        row(notes[0]).performTouchInput { longClick() }

        row(notes[1]).performTouchInput { click(centerRight - Offset(8f, 0f)) }
        assertSelection(notes[0], notes[1])
        row(notes[1]).assertChecked(ToggleableState.On)
        touchCheckboxArea(notes[1])
        assertSelection(notes[0])
        row(notes[1]).assertChecked(ToggleableState.Off)
        touchCheckboxArea(notes[1])
        assertSelection(notes[0], notes[1])
        row(notes[1]).assertChecked(ToggleableState.On)

        row(notes[0]).performClick()
        assertSelection(notes[1])
        touchCheckboxArea(notes[1])
        assertSelection()
        assertNormalToolbar()
        row(notes[1]).assert(role(Role.Button)).performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf(notes[1].id), openedNotes) }
    }

    @Test fun selectAllIncludesOffscreenNotesReportsPartialAndAllAndCanClearEverything() {
        val notes = seedLegacyNotes(40)
        showNotes()
        compose.onNodeWithText(title(notes.last())).assertDoesNotExist()
        row(notes[0]).performTouchInput { longClick() }
        selectAll().assertChecked(ToggleableState.Indeterminate).performClick()
        assertSelection(*notes.toTypedArray())
        selectAll().assertChecked(ToggleableState.On)
        assertSelectionToolbar(notes.size)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title(notes.last())))
        row(notes.last()).assertIsDisplayed().assertChecked(ToggleableState.On).performClick()
        assertSelection(*notes.dropLast(1).toTypedArray())
        row(notes.last()).assertChecked(ToggleableState.Off)
        selectAll().assertChecked(ToggleableState.Indeterminate).performClick()
        assertSelection(*notes.toTypedArray())
        row(notes.last()).assertChecked(ToggleableState.On)
        selectAll().assertChecked(ToggleableState.On).performClick()
        assertSelection()
        assertNormalToolbar()
        compose.runOnIdle { assertTrue(openedNotes.isEmpty()) }
    }

    @Test fun accessibleClearActionExitsSelectionWithoutChangingStoredNotes() {
        val notes = seedLegacyNotes(3)
        showNotes()
        row(notes[0]).performTouchInput { longClick() }
        row(notes[1]).performClick()

        icon(R.string.clear_note_selection).assertMinimumTouchTarget().performClick()
        assertSelection()
        assertNormalToolbar()
        assertStoredNotes(notes)
        compose.runOnIdle { assertTrue(openedNotes.isEmpty()) }
    }

    @Test fun cancelAndDialogBackKeepSelectionWhileNextBackClearsBeforeNavigating() {
        val notes = seedLegacyNotes(3)
        showNotes()
        row(notes[0]).performTouchInput { longClick() }
        row(notes[1]).performClick()
        requestDeletion(2)
        compose.runOnIdle {
            assertEquals(notes.take(2).map { it.id }, viewModel.uiState.value.pendingDeletionIds)
        }

        text(R.string.cancel).performClick()
        assertNoConfirmation(2)
        assertSelection(notes[0], notes[1])
        assertStoredNotes(notes)

        requestDeletion(2)
        pressBack()
        assertNoConfirmation(2)
        assertSelection(notes[0], notes[1])
        assertStoredNotes(notes)
        compose.runOnIdle { assertEquals(0, navigationBacks) }

        pressBack()
        assertSelection()
        assertNormalToolbar()
        compose.runOnIdle { assertEquals(0, navigationBacks) }
        pressBack()
        compose.runOnIdle {
            assertEquals(1, navigationBacks)
            assertTrue(openedNotes.isEmpty())
        }
    }

    @Test fun themeRecompositionPreservesSelectionAndThePendingConfirmation() {
        val notes = seedLegacyNotes(3)
        showNotes()
        row(notes[0]).performTouchInput { longClick() }
        row(notes[1]).performClick()
        requestDeletion(2)

        compose.runOnIdle { themeMode = ThemeMode.DARK }
        compose.runOnIdle {
            assertTrue(backgroundLuminance < 0.1f)
            assertEquals(notes.take(2).map { it.id }, viewModel.uiState.value.pendingDeletionIds)
        }
        confirmation(2).assertIsDisplayed()
        assertSelection(notes[0], notes[1])
        text(R.string.cancel).performClick()
        row(notes[0]).assertChecked(ToggleableState.On)
        row(notes[1]).assertChecked(ToggleableState.On)

        compose.runOnIdle { themeMode = ThemeMode.LIGHT }
        compose.runOnIdle { assertTrue(backgroundLuminance > 0.9f) }
        assertSelection(notes[0], notes[1])
        assertSelectionToolbar(2)
        row(notes[2]).assertChecked(ToggleableState.Off)
        compose.runOnIdle { assertTrue(openedNotes.isEmpty()) }
    }

    @Test fun subsetDeletionPreservesUnselectedRecordsAndImagesUntilTheirLastReference() {
        val notes = seedMixedNotes()
        val paragraph0 = notes[0]
        val paragraph1 = notes[1]
        val legacyBatchReference = notes[2]
        val legacy0 = notes[3]
        val legacy1 = notes[4]
        val unrelated = notes[5]
        val sourceImage = runBlocking { disk.read(paragraph0.imagePath).getOrThrow() }
        val legacyImage = runBlocking { disk.read(legacy0.imagePath).getOrThrow() }
        showNotes()
        row(paragraph0).performTouchInput { longClick() }
        scrollTo(legacy0).performClick()
        deleteSelection(2, notes - setOf(paragraph0, legacy0))
        assertStoredNotes(notes - setOf(paragraph0, legacy0))
        assertImage(paragraph0.imagePath, sourceImage)
        assertImage(legacy0.imagePath, legacyImage)

        scrollTo(paragraph1).performTouchInput { longClick() }
        deleteSelection(1, listOf(legacyBatchReference, legacy1, unrelated))
        assertStoredNotes(listOf(legacyBatchReference, legacy1, unrelated))
        assertImage(paragraph0.imagePath, sourceImage)
        runBlocking {
            assertTrue(freshRepository().isSourceCommitted(storage.id).getOrThrow())
        }

        scrollTo(legacyBatchReference).performTouchInput { longClick() }
        scrollTo(legacy1).performClick()
        deleteSelection(2, listOf(unrelated))
        assertStoredNotes(listOf(unrelated))
        runBlocking {
            assertFalse(disk.exists(paragraph0.imagePath).getOrThrow())
            assertFalse(disk.exists(legacy0.imagePath).getOrThrow())
            assertTrue(disk.exists(unrelated.imagePath).getOrThrow())
        }
        assertNormalToolbar()
        row(unrelated).performClick()
        compose.runOnIdle { assertEquals(listOf(unrelated.id), openedNotes) }
    }

    @Test fun deletingAllMixedParagraphAndLegacyNotesPersistsAnEmptyLibraryAndRemovesImages() {
        val notes = seedMixedNotes()
        showNotes()
        row(notes[0]).performTouchInput { longClick() }
        selectAll().performClick()
        deleteSelection(notes.size, emptyList())

        assertStoredNotes(emptyList())
        notes.forEach { compose.onNodeWithText(title(it)).assertDoesNotExist() }
        compose.onNodeWithText("Take a photo or choose an image").assertIsDisplayed()
        icon(R.string.settings).assertIsEnabled()
        icon(R.string.clear_note_selection).assertDoesNotExist()
        icon(R.string.delete_selected_notes).assertDoesNotExist()
        selectAll().assertDoesNotExist()
        runBlocking {
            assertTrue(disk.list("notes").getOrThrow().isEmpty())
            assertTrue(disk.list("images").getOrThrow().isEmpty())
            assertTrue(disk.list("deletions").getOrThrow().isEmpty())
            assertTrue(freshRepository().isSourceCommitted(storage.id).getOrThrow())
        }
        compose.runOnIdle { assertTrue(openedNotes.isEmpty()) }
    }

    @Test fun intentWriteFailureStopsTheBatchKeepsRemainingSelectionAndAllowsExplicitRetry() {
        val notes = seedLegacyNotes(3)
        val controlled = ControlledFileStore(disk, failOnceFor = notes[1].id)
        showNotes(controlled)
        row(notes[0]).performTouchInput { longClick() }
        selectAll().performClick()
        requestDeletion(3)
        text(R.string.delete_notes_confirm).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            !viewModel.uiState.value.isDeleting && viewModel.uiState.value.deletionError != null
        }

        compose.onNodeWithText(compose.activity.getString(R.string.notes_delete_error, FAILURE))
            .assertIsDisplayed()
        assertSelection(notes[1], notes[2])
        row(notes[1]).assertChecked(ToggleableState.On).assertIsEnabled()
        row(notes[2]).assertChecked(ToggleableState.On).assertIsEnabled()
        assertSelectionToolbar(2)
        assertStoredNotes(notes.drop(1))
        compose.runOnIdle {
            assertNull(viewModel.uiState.value.error)
            assertNull(viewModel.uiState.value.deletedCount)
            assertTrue(viewModel.uiState.value.pendingDeletionIds.isEmpty())
        }
        assertEquals(notes.take(2).map { intentPath(it.id) }, controlled.intentWrites.toList())
        runBlocking {
            assertFalse(disk.exists(notes[0].imagePath).getOrThrow())
            assertTrue(disk.exists(notes[1].imagePath).getOrThrow())
            assertTrue(disk.exists(notes[2].imagePath).getOrThrow())
            assertTrue(disk.list("deletions").getOrThrow().isEmpty())
        }

        deleteSelection(2, emptyList())
        assertStoredNotes(emptyList())
        compose.onNodeWithText(compose.activity.getString(R.string.notes_delete_error, FAILURE))
            .assertDoesNotExist()
        assertEquals(
            listOf(notes[0], notes[1], notes[1], notes[2]).map { intentPath(it.id) },
            controlled.intentWrites.toList(),
        )
    }

    @Test fun failedImageCleanupShowsLoadErrorAndRetryReconcilesWithoutAnotherDeletion() {
        val notes = seedLegacyNotes(2)
        val cleanupFailure = "Simulated source-image cleanup failure"
        val blockCleanup = AtomicBoolean(true)
        val intentWrites = CopyOnWriteArrayList<String>()
        val deletionPaths = CopyOnWriteArrayList<String>()
        val unrelatedImage = "images/cleanup-sentinel.png"
        val unrelatedBytes = imageBytes()
        runBlocking { disk.write(unrelatedImage, unrelatedBytes).getOrThrow() }
        val failingCleanup = object : FileStore by disk {
            override suspend fun write(path: String, data: ByteArray): Result<Unit> {
                if (path.startsWith("deletions/")) intentWrites += path
                return disk.write(path, data)
            }

            override suspend fun delete(path: String): Result<Unit> {
                deletionPaths += path
                return if (path == notes.last().imagePath && blockCleanup.get()) {
                    Result.failure(IOException(cleanupFailure))
                } else {
                    disk.delete(path)
                }
            }
        }
        showNotes(failingCleanup)
        row(notes[0]).performTouchInput { longClick() }
        selectAll().performClick()
        requestDeletion(2)
        text(R.string.delete_notes_confirm).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            !viewModel.uiState.value.isDeleting && viewModel.uiState.value.error != null
        }

        val loadError = compose.activity.getString(R.string.notes_load_error, cleanupFailure)
        compose.onNodeWithText(loadError).assertIsDisplayed()
        text(R.string.retry).assertIsDisplayed().assertIsEnabled()
        icon(R.string.delete_selected_notes).assertIsNotEnabled()
        selectAll().assertIsNotEnabled()
        assertSelection(*notes.toTypedArray())
        assertNoConfirmation(2)
        notes.forEach { compose.onNodeWithText(title(it)).assertDoesNotExist() }
        compose.runOnIdle { assertNull(viewModel.uiState.value.deletedCount) }
        // Inspect raw storage: a fresh repository would itself retry the pending cleanup.
        runBlocking {
            assertTrue(disk.list("notes").getOrThrow().isEmpty())
            assertTrue(disk.exists(notes.last().imagePath).getOrThrow())
            assertTrue(disk.exists(intentPath(notes.last().id)).getOrThrow())
        }
        icon(R.string.delete_selected_notes).performTouchInput { click() }
        assertNoConfirmation(2)
        assertEquals(notes.map { intentPath(it.id) }, intentWrites.toList())

        blockCleanup.set(false)
        text(R.string.retry).performClick()
        awaitNotes(emptyList())
        assertSelection()
        compose.onNodeWithText(loadError).assertDoesNotExist()
        text(R.string.retry).assertDoesNotExist()
        compose.onNodeWithText("Take a photo or choose an image").assertIsDisplayed()
        icon(R.string.settings).assertIsEnabled()
        icon(R.string.clear_note_selection).assertDoesNotExist()
        icon(R.string.delete_selected_notes).assertDoesNotExist()
        selectAll().assertDoesNotExist()
        assertStoredNotes(emptyList())
        runBlocking {
            notes.forEach { assertFalse(disk.exists(it.imagePath).getOrThrow()) }
            assertTrue(disk.list("deletions").getOrThrow().isEmpty())
        }
        assertImage(unrelatedImage, unrelatedBytes)
        assertEquals(notes.map { intentPath(it.id) }, intentWrites.toList())
        assertEquals(notes.map { "notes/${it.id}.json" }, deletionPaths.filter { it.startsWith("notes/") })
        assertFalse(unrelatedImage in deletionPaths)
        compose.runOnIdle {
            assertNull(viewModel.uiState.value.deletedCount)
            assertTrue(viewModel.uiState.value.pendingDeletionIds.isEmpty())
            assertTrue(openedNotes.isEmpty())
        }
    }

    @Test fun deletionProgressDisablesRowsAndToolbarAndBackCannotAbandonTheBatch() {
        val notes = seedLegacyNotes(3)
        val controlled = ControlledFileStore(disk, pauseFor = notes[0].id)
        showNotes(controlled)
        row(notes[0]).performTouchInput { longClick() }
        row(notes[1]).performClick()
        requestDeletion(2)
        text(R.string.delete_notes_confirm).performClick()

        try {
            compose.waitUntil(TIMEOUT_MS) { controlled.writeStarted.isCompleted }
            text(R.string.deleting_notes).assertIsDisplayed()
            confirmation(2).assertDoesNotExist()
            icon(R.string.clear_note_selection).assertIsNotEnabled()
            icon(R.string.delete_selected_notes).assertIsNotEnabled()
            selectAll().assertIsNotEnabled()
            notes.forEach { row(it).assertIsNotEnabled() }
            icon(R.string.settings).assertDoesNotExist()
            icon(R.string.add_notes).assertDoesNotExist()
            icon(R.string.take_photo).assertDoesNotExist()

            row(notes[0]).performTouchInput { click() }
            row(notes[2]).performTouchInput { longClick() }
            touchCheckboxArea(notes[1])
            selectAll().performTouchInput { click() }
            icon(R.string.clear_note_selection).performTouchInput { click() }
            icon(R.string.delete_selected_notes).performTouchInput { click() }
            pressBack()
            assertSelection(notes[0], notes[1])
            compose.runOnIdle {
                assertTrue(viewModel.uiState.value.isDeleting)
                assertTrue(viewModel.uiState.value.pendingDeletionIds.isEmpty())
                assertTrue(openedNotes.isEmpty())
                assertEquals(0, navigationBacks)
                assertEquals(0, sourceReadyCount)
                assertEquals(0, cameraClicks)
                assertEquals(0, settingsClicks)
            }
            assertEquals(listOf(intentPath(notes[0].id)), controlled.intentWrites.toList())
        } finally {
            controlled.continueWrite.complete(Unit)
            compose.waitUntil(TIMEOUT_MS) { !viewModel.uiState.value.isDeleting }
        }

        awaitNotes(notes.drop(2))
        text(R.string.deleting_notes).assertDoesNotExist()
        assertSelection()
        assertNormalToolbar()
        row(notes[2]).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(notes[2].id), openedNotes) }
        assertStoredNotes(notes.drop(2))
        assertEquals(notes.take(2).map { intentPath(it.id) }, controlled.intentWrites.toList())
    }

    private fun showNotes(fileStore: FileStore = disk) {
        compose.runOnUiThread {
            viewModel = NotesListViewModel(NoteRepository(fileStore))
            models.put("notes", viewModel)
        }
        compose.setContent {
            FPInkTheme(themeMode, compose.activity) {
                val luminance = MaterialTheme.colorScheme.background.luminance()
                SideEffect { backgroundLuminance = luminance }
                BackHandler { navigationBacks++ }
                val source = remember { SourceImportViewModel(storage.images) }
                NotesListScreen(
                    onSourceReady = { sourceReadyCount++ },
                    onCameraClick = { cameraClicks++ },
                    onNoteClick = { openedNotes += it },
                    onSettingsClick = { settingsClicks++ },
                    viewModel = viewModel,
                    sourceViewModel = source,
                )
            }
        }
        compose.waitUntil(TIMEOUT_MS) { !viewModel.uiState.value.isLoading }
        compose.runOnIdle { assertNull(viewModel.uiState.value.error) }
    }

    private fun row(note: Note): SemanticsNodeInteraction =
        compose.onNode(hasText(title(note)) and hasClickAction())

    private fun scrollTo(note: Note): SemanticsNodeInteraction {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(title(note)))
        return row(note).assertIsDisplayed()
    }

    private fun title(note: Note): String = note.text.lineSequence().first()

    private fun icon(resource: Int) =
        compose.onNodeWithContentDescription(compose.activity.getString(resource))

    private fun text(resource: Int) = compose.onNodeWithText(compose.activity.getString(resource))

    private fun selectAll() = text(R.string.select_all_notes)

    private fun confirmation(count: Int) =
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.delete_notes_confirmation, count, count))

    private fun role(value: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, value)

    private fun SemanticsNodeInteraction.assertChecked(value: ToggleableState): SemanticsNodeInteraction =
        assert(role(Role.Checkbox)).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, value))

    private fun SemanticsNodeInteraction.assertMinimumTouchTarget(): SemanticsNodeInteraction =
        assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

    private fun touchCheckboxArea(note: Note) {
        // The visual checkbox has no callback: touch its area on the merged, toggleable row.
        val checkboxCenterX = with(compose.density) { 20.dp.toPx() }
        row(note).performTouchInput { click(Offset(checkboxCenterX, center.y)) }
    }

    private fun assertSelection(vararg expected: Note) {
        compose.runOnIdle {
            assertEquals(expected.map { it.id }.toSet(), viewModel.uiState.value.selectedIds)
        }
    }

    private fun assertNormalToolbar() {
        icon(R.string.settings).assertIsDisplayed().assertIsEnabled()
        icon(R.string.add_notes).assertIsDisplayed().assertIsEnabled()
        icon(R.string.take_photo).assertIsDisplayed().assertIsEnabled()
        icon(R.string.clear_note_selection).assertDoesNotExist()
        icon(R.string.delete_selected_notes).assertDoesNotExist()
        selectAll().assertDoesNotExist()
    }

    private fun assertSelectionToolbar(count: Int) {
        compose.onNodeWithText(
            compose.activity.resources.getQuantityString(R.plurals.notes_selected, count, count),
        ).assertIsDisplayed()
        icon(R.string.clear_note_selection).assertMinimumTouchTarget().assertIsEnabled()
        icon(R.string.delete_selected_notes).assertMinimumTouchTarget().assertIsEnabled()
        icon(R.string.settings).assertDoesNotExist()
        icon(R.string.add_notes).assertDoesNotExist()
        icon(R.string.take_photo).assertDoesNotExist()
    }

    private fun requestDeletion(count: Int) {
        icon(R.string.delete_selected_notes).performClick()
        confirmation(count).assertIsDisplayed()
        text(R.string.delete_notes_confirm).assertIsEnabled()
        text(R.string.cancel).assertIsEnabled()
    }

    private fun assertNoConfirmation(count: Int) {
        confirmation(count).assertDoesNotExist()
        compose.runOnIdle { assertTrue(viewModel.uiState.value.pendingDeletionIds.isEmpty()) }
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun deleteSelection(count: Int, remaining: List<Note>) {
        requestDeletion(count)
        text(R.string.delete_notes_confirm).performClick()
        awaitNotes(remaining)
        assertSelection()
        compose.onAllNodesWithText(
            compose.activity.resources.getQuantityString(R.plurals.notes_deleted, count, count),
        ).assertCountEquals(1)
        compose.runOnIdle {
            assertNull(viewModel.uiState.value.deletionError)
            assertTrue(viewModel.uiState.value.pendingDeletionIds.isEmpty())
        }
    }

    private fun awaitNotes(expected: List<Note>) {
        compose.waitUntil(TIMEOUT_MS) {
            val state = viewModel.uiState.value
            !state.isLoading && !state.isDeleting && state.error == null &&
                state.notes.map { it.id }.toSet() == expected.map { it.id }.toSet()
        }
    }

    private fun freshRepository() = NoteRepository(AndroidFileStore(storage.context))

    private fun assertStoredNotes(expected: List<Note>) = runBlocking {
        val restored = freshRepository()
        assertEquals(expected.associateBy { it.id }, restored.list().getOrThrow().associateBy { it.id })
        expected.forEach { assertEquals(it, restored.get(it.id).getOrThrow()) }
    }

    private fun assertImage(path: String, expected: ByteArray) = runBlocking {
        assertArrayEquals(expected, AndroidFileStore(storage.context).read(path).getOrThrow())
    }

    private fun seedLegacyNotes(count: Int): List<Note> = runBlocking {
        val image = imageBytes()
        val repository = NoteRepository(disk)
        (0 until count).map { index ->
            legacyNote("legacy-$index", 2_000L - index).also {
                disk.write(it.imagePath, image).getOrThrow()
                repository.save(it).getOrThrow()
            }
        }
    }

    private fun seedMixedNotes(): List<Note> = runBlocking {
        val sourceId = storage.id
        val image = imageBytes()
        val repository = NoteRepository(disk)
        val paragraphs = (0..1).map { index ->
            legacyNote("$sourceId-$index", 3_000).copy(
                text = "Paragraph $index\nAdditional paragraph text",
                imagePath = "images/$sourceId.png",
                sourceId = sourceId,
                paragraphIndex = index,
            )
        }
        repository.saveBatch(sourceId, image, "png", paragraphs).getOrThrow()
        val legacy = listOf(
            legacyNote("legacy-batch-reference", 2_000).copy(imagePath = paragraphs[0].imagePath),
            legacyNote("legacy-shared-0", 1_900).copy(imagePath = "images/legacy-shared.jpg"),
            legacyNote("legacy-shared-1", 1_800).copy(imagePath = "images/legacy-shared.jpg"),
            legacyNote("unrelated", 1_700),
        )
        legacy.forEach {
            if (!disk.exists(it.imagePath).getOrThrow()) disk.write(it.imagePath, image).getOrThrow()
            repository.save(it).getOrThrow()
        }
        paragraphs + legacy
    }

    private fun legacyNote(id: String, timestamp: Long) = Note(
        id = id,
        capturedAt = Instant.fromEpochSeconds(timestamp),
        imagePath = "images/$id.jpg",
        text = "Note $id\nSecond line of $id",
        inkColorHex = "#112233",
        inkColorName = "Blue",
        inkColorOrigin = InkColorOrigin.USER_SELECTED,
        userEdited = true,
        tags = listOf("acceptance"),
    )

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(Color.WHITE)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class ControlledFileStore(
        private val disk: FileStore,
        private val failOnceFor: String? = null,
        private val pauseFor: String? = null,
    ) : FileStore by disk {
        val intentWrites = CopyOnWriteArrayList<String>()
        val writeStarted = CompletableDeferred<Unit>()
        val continueWrite = CompletableDeferred<Unit>()
        private val failed = AtomicBoolean(false)

        override suspend fun write(path: String, data: ByteArray): Result<Unit> {
            if (path.startsWith("deletions/")) {
                intentWrites += path
                if (path == failOnceFor?.let(::intentPath) && failed.compareAndSet(false, true)) {
                    return Result.failure(IOException(FAILURE))
                }
                if (path == pauseFor?.let(::intentPath) && !writeStarted.isCompleted) {
                    writeStarted.complete(Unit)
                    withTimeout(TIMEOUT_MS * 3) { continueWrite.await() }
                }
            }
            return disk.write(path, data)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val FAILURE = "Simulated deletion-intent write failure"
        fun intentPath(id: String) = "deletions/$id.json"
    }
}
