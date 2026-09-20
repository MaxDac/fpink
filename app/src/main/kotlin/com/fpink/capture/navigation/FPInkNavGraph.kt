package com.fpink.capture.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.fpink.capture.ui.capture.CaptureScreen
import com.fpink.capture.ui.detail.NoteDetailScreen
import com.fpink.capture.ui.notes.NotesListScreen
import com.fpink.capture.ui.processing.ProcessingScreen
import com.fpink.capture.ui.settings.SettingsScreen
import com.fpink.capture.data.ThemeMode
import com.fpink.capture.ui.theme.ThemeUiState

object Routes {
    const val NOTES_LIST = "notes_list"
    const val CAPTURE = "capture"
    const val PROCESSING = "processing/{sourceId}"
    const val NOTE_DETAIL = "note_detail/{noteId}"
    const val SETTINGS = "settings"

    fun processing(sourceId: String) = "processing/$sourceId"
    fun noteDetail(noteId: String) = "note_detail/$noteId"
}

@Composable
fun FPInkNavGraph(
    navController: NavHostController,
    appearance: ThemeUiState,
    onThemeSelected: (ThemeMode) -> Unit,
) {
    NavHost(navController = navController, startDestination = Routes.NOTES_LIST) {
        composable(Routes.NOTES_LIST) { entry ->
            val result by entry.savedStateHandle.getStateFlow<String?>("createdNotesMessage", null).collectAsStateWithLifecycle()
            NotesListScreen(
                onCaptureClick = { navController.navigate(Routes.CAPTURE) },
                onNoteClick = { noteId -> navController.navigate(Routes.noteDetail(noteId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                resultMessage = result,
                onResultShown = { entry.savedStateHandle.set<String?>("createdNotesMessage", null) },
            )
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onImageCaptured = { uri -> navController.navigate(Routes.processing(uri)) },
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.PROCESSING) {
            ProcessingScreen(
                onComplete = { count, cleanupWarning, previouslySaved ->
                    val message = when {
                        previouslySaved && count == 0 ->
                            "This image was already processed; its notes have been deleted. No notes were recreated."
                        previouslySaved -> "$count ${if (count == 1) "note" else "notes"} already saved."
                        else -> "$count ${if (count == 1) "note" else "notes"} created."
                    }
                    navController.getBackStackEntry(Routes.NOTES_LIST).savedStateHandle["createdNotesMessage"] =
                        message +
                        if (cleanupWarning) " The staged import could not be cleaned up; saved notes are safe." else ""
                    navController.popBackStack(Routes.NOTES_LIST, false)
                },
                onDiscard = { navController.popBackStack(Routes.NOTES_LIST, false) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
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
