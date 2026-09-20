package com.fpink.capture

import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.rememberNavController
import com.fpink.capture.navigation.FPInkNavGraph
import com.fpink.capture.ui.theme.FPInkTheme
import com.fpink.capture.ui.theme.ThemeError
import com.fpink.capture.ui.theme.ThemeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val theme = ViewModelProvider(this, viewModelFactory {
            initializer { ThemeViewModel((application as FPInkApp).container.settingsStore) }
        })[ThemeViewModel::class.java]
        // Keep the platform starting window until the persisted choice is known.
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (theme.uiState.value.loading) return false
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        setContent {
            val appearance by theme.uiState.collectAsStateWithLifecycle()
            FPInkTheme(appearance.mode, this) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (appearance.loading || appearance.error == ThemeError.READ) {
                        Column(
                            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (appearance.loading) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.appearance_loading))
                            } else {
                                Text(stringResource(R.string.appearance_read_error))
                                Button(onClick = theme::retryLoad) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    } else {
                        val navController = rememberNavController()
                        FPInkNavGraph(
                            navController = navController,
                            appearance = appearance,
                            onThemeSelected = theme::select,
                        )
                    }
                }
            }
        }
    }
}
