package com.fpink.capture.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.fpink.capture.ui.capture.CaptureScreen
import com.fpink.capture.ui.detail.NoteDetailScreen
import com.fpink.capture.ui.notes.AndroidClipboardImageReader
import com.fpink.capture.ui.notes.NotesListScreen
import com.fpink.capture.ui.notes.NotesListViewModel
import com.fpink.capture.ui.notes.SourceImportViewModel
import com.fpink.capture.ui.containerViewModel
import com.fpink.capture.ui.processing.ProcessingScreen
import com.fpink.capture.ui.review.CaptureReviewScreen
import com.fpink.capture.ui.settings.SettingsScreen
import com.fpink.capture.data.ThemeMode
import com.fpink.capture.ui.theme.ThemeUiState

object Routes {
    const val NOTES_LIST = "notes_list"
    const val CAPTURE = "capture"
    const val CAMERA_ENTRY = "openCamera"
    const val SOURCE_ID = "sourceId"
    const val CAPTURE_DESTINATION = "$CAPTURE?$CAMERA_ENTRY={$CAMERA_ENTRY}&$SOURCE_ID={$SOURCE_ID}"
    const val PROCESSING = "processing/{sourceId}"
    const val CAPTURE_REVIEW = "capture_review/{sourceId}"
    const val NOTE_DETAIL = "note_detail/{noteId}"
    const val SETTINGS = "settings"

    fun processing(sourceId: String) = "processing/$sourceId"
    fun captureReview(sourceId: String) = "capture_review/$sourceId"
    fun noteDetail(noteId: String) = "note_detail/$noteId"

    /** [sourceId] is set when an image was already staged (e.g. from the notes list's Gallery/File/Paste
     * menu), so [com.fpink.capture.ui.capture.CaptureScreen] opens directly on its review step. */
    fun capture(openCamera: Boolean, sourceId: String? = null) =
        "$CAPTURE?$CAMERA_ENTRY=$openCamera" + (sourceId?.let { "&$SOURCE_ID=$it" } ?: "")
}

@Composable
fun FPInkNavGraph(
    navController: NavHostController,
    appearance: ThemeUiState,
    onThemeSelected: (ThemeMode) -> Unit,
) {
    NavHost(navController = navController, startDestination = Routes.NOTES_LIST) {
        composable(Routes.NOTES_LIST) { entry ->
            NotesListDestination(navController, entry)
        }
        composable(
            Routes.CAPTURE_DESTINATION,
            arguments = listOf(
                navArgument(Routes.CAMERA_ENTRY) { type = NavType.BoolType; defaultValue = false },
                navArgument(Routes.SOURCE_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            CaptureScreen(
                onImageCaptured = { uri -> navController.navigate(Routes.processing(uri)) },
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                startWithCamera = entry.arguments?.getBoolean(Routes.CAMERA_ENTRY) == true,
            )
        }
        composable(Routes.PROCESSING) {
            ProcessingScreen(
                onComplete = { sourceId, count, cleanupWarning, previouslySaved ->
                    navController.getBackStackEntry(Routes.NOTES_LIST).savedStateHandle["createdNotesMessage"] =
                        captureCompletionMessage(count, cleanupWarning, previouslySaved)
                    if (shouldReviewCapture(count, previouslySaved)) {
                        navController.navigate(Routes.captureReview(sourceId)) {
                            popUpTo(Routes.NOTES_LIST) { inclusive = false }
                            launchSingleTop = true
                        }
                    } else {
                        navController.popBackStack(Routes.NOTES_LIST, false)
                    }
                },
                onDiscard = { navController.popBackStack(Routes.NOTES_LIST, false) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.CAPTURE_REVIEW) { backStackEntry ->
            val sourceId = backStackEntry.arguments?.getString("sourceId") ?: ""
            CaptureReviewScreen(
                sourceId = sourceId,
                onComplete = { navController.popBackStack(Routes.NOTES_LIST, false) },
                onExit = { navController.popBackStack(Routes.NOTES_LIST, false) },
            )
        }
        composable(Routes.NOTE_DETAIL) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId") ?: ""
            NoteDetailScreen(
                noteId = noteId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                appearance = appearance,
                onThemeSelected = onThemeSelected,
            )
        }
    }
}

internal fun shouldReviewCapture(count: Int, previouslySaved: Boolean): Boolean =
    !previouslySaved && count > 0

internal fun captureCompletionMessage(
    count: Int,
    cleanupWarning: Boolean,
    previouslySaved: Boolean,
): String {
    val message = when {
        previouslySaved && count == 0 ->
            "This image was already processed; its notes have been deleted. No notes were recreated."
        previouslySaved -> "$count ${if (count == 1) "note" else "notes"} already saved."
        else -> "$count ${if (count == 1) "note" else "notes"} created."
    }
    return message +
        if (cleanupWarning) " The staged import could not be cleaned up; saved notes are safe." else ""
}

@Composable
internal fun NotesListDestination(
    navController: NavHostController,
    entry: NavBackStackEntry,
    viewModel: NotesListViewModel = containerViewModel { NotesListViewModel(it.noteRepository) },
    sourceViewModel: SourceImportViewModel = run {
        val context = LocalContext.current
        containerViewModel { SourceImportViewModel(it.imageImports, AndroidClipboardImageReader(context)) }
    },
) {
    val result by entry.savedStateHandle.getStateFlow<String?>("createdNotesMessage", null).collectAsStateWithLifecycle()
    fun openCapture(openCamera: Boolean, sourceId: String? = null) {
        // Ignore rapid taps (including the other action) after this entry starts leaving.
        if (navController.currentBackStackEntry == entry && entry.lifecycle.currentState == Lifecycle.State.RESUMED) {
            navController.navigate(Routes.capture(openCamera, sourceId)) { launchSingleTop = true }
        }
    }
    NotesListScreen(
        onSourceReady = { sourceId -> openCapture(false, sourceId) },
        onCameraClick = { openCapture(true) },
        onNoteClick = { noteId -> navController.navigate(Routes.noteDetail(noteId)) },
        onSettingsClick = { navController.navigate(Routes.SETTINGS) },
        resultMessage = result,
        onResultShown = { entry.savedStateHandle.set<String?>("createdNotesMessage", null) },
        viewModel = viewModel,
        sourceViewModel = sourceViewModel,
    )
}
