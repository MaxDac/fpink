package com.fpink.capture

import android.app.Application
import com.fpink.capture.diagnostics.DiagnosticStore
import java.io.File

class FPInkApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val diagnostics = DiagnosticStore(
            File(noBackupFilesDir, "diagnostics"),
            File(cacheDir, "diagnostics-share"),
        )
        diagnostics.installCrashHandler()
        container = AppContainer(this, diagnostics)
    }
}
