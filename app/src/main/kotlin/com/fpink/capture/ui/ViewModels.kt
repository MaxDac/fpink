package com.fpink.capture.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fpink.capture.AppContainer
import com.fpink.capture.FPInkApp

/** Access the app-wide manual DI container from a composable. */
@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as FPInkApp).container

/**
 * Creates (or reuses) a [ViewModel] built from the [AppContainer], hiding the
 * ViewModelProvider.Factory boilerplate that manual DI otherwise requires.
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    crossinline factory: (AppContainer) -> VM,
): VM {
    val container = appContainer()
    return viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return factory(container) as T
            }
        },
    )
}
