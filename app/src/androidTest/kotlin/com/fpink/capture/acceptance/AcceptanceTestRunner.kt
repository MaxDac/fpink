package com.fpink.capture.acceptance

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Do not initialize FPInkApp: its startup migration must never touch an installed user's settings. */
class AcceptanceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
