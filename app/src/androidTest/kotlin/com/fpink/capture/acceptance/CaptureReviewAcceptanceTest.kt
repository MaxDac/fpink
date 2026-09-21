package com.fpink.capture.acceptance

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fpink.capture.data.AndroidFileStore
import com.fpink.capture.ui.review.CaptureReviewScreen
import com.fpink.capture.ui.review.CaptureReviewViewModel
import com.fpink.core.model.Note
import com.fpink.core.storage.NoteRepository
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureReviewAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var storage: AcceptanceStorage
    private lateinit var repository: NoteRepository
    private lateinit var viewModel: CaptureReviewViewModel
    private val models = ViewModelStore()
    private var completions = 0
    private var exits = 0

    @Before fun setUp() {
        storage = AcceptanceStorage()
        repository = NoteRepository(AndroidFileStore(storage.context))
        val sourceId = storage.id
        runBlocking {
            repository.saveBatch(
                sourceId,
                byteArrayOf(1),
                "png",
                (0..1).map { index ->
                    Note(
                        id = "$sourceId-$index",
                        capturedAt = Instant.fromEpochSeconds(1_000),
                        imagePath = "images/$sourceId.png",
                        text = "Captured paragraph $index",
                        sourceId = sourceId,
                        paragraphIndex = index,
                    )
                },
            ).getOrThrow()
        }
        compose.runOnUiThread {
            viewModel = CaptureReviewViewModel(sourceId, repository)
            models.put("review", viewModel)
        }
        compose.setContent {
            MaterialTheme {
                CaptureReviewScreen(
                    sourceId = sourceId,
                    onComplete = { completions++ },
                    onExit = { exits++ },
                    viewModel = viewModel,
                )
            }
        }
        compose.waitUntil(10_000) { !viewModel.uiState.value.isLoading }
    }

    @After fun tearDown() {
        compose.runOnUiThread { models.clear() }
        storage.close()
    }

    @Test fun editsMultipleNotesAndSavesThemWithOneDoneAction() {
        compose.onNodeWithText("Captured paragraph 0").assertIsDisplayed()
            .performTextReplacement("Corrected first paragraph")
        compose.onNodeWithText("Captured paragraph 1").assertIsDisplayed()
            .performTextReplacement("Corrected second paragraph")

        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(10_000) { completions == 1 }

        val notes = runBlocking { repository.list().getOrThrow() }
        assertEquals(listOf("Corrected first paragraph", "Corrected second paragraph"), notes.map { it.text })
        assertEquals(listOf(true, true), notes.map { it.userEdited })
        assertEquals(0, exits)
    }

    @Test fun backWithChangesCanKeepReviewingOrDiscardOnlyTheCorrections() {
        compose.onNodeWithText("Captured paragraph 0").performTextReplacement("Unsaved correction")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard corrections?").assertIsDisplayed()

        compose.onNodeWithText("Keep reviewing").performClick()
        assertEquals(0, exits)
        compose.onNodeWithText("Unsaved correction").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes").performClick()
        compose.runOnIdle { assertEquals(1, exits) }

        val notes = runBlocking { repository.list().getOrThrow() }
        assertEquals("Captured paragraph 0", notes.first().text)
        assertFalse(notes.first().userEdited)
        assertEquals(0, completions)
    }
}
