package com.fpink.capture.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.fpink.capture.ui.capture.CaptureScreen
import com.fpink.capture.ui.detail.NoteDetailScreen
import com.fpink.capture.ui.notes.NotesListScreen
import com.fpink.capture.ui.processing.ProcessingScreen
import com.fpink.capture.ui.settings.SettingsScreen

object Routes {
    const val NOTES_LIST = "notes_list"
    const val CAPTURE = "capture"
    const val PROCESSING = "processing/{imageUri}"
    const val NOTE_DETAIL = "note_detail/{noteId}"
    const val SETTINGS = "settings"

    fun processing(imageUri: String) = "processing/$imageUri"
    fun noteDetail(noteId: String) = "note_detail/$noteId"
}

@Composable
fun FPInkNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.NOTES_LIST) {
        composable(Routes.NOTES_LIST) {
            NotesListScreen(
                onCaptureClick = { navController.navigate(Routes.CAPTURE) },
                onNoteClick = { noteId -> navController.navigate(Routes.noteDetail(noteId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onImageCaptured = { uri -> navController.navigate(Routes.processing(uri)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PROCESSING) { backStackEntry ->
            val imageUri = backStackEntry.arguments?.getString("imageUri") ?: ""
            ProcessingScreen(
                imageUri = imageUri,
                onComplete = { noteId ->
                    navController.navigate(Routes.noteDetail(noteId)) {
                        popUpTo(Routes.NOTES_LIST)
                    }
                },
                onDiscard = { navController.popBackStack(Routes.NOTES_LIST, false) },
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
            )
        }
    }
}
