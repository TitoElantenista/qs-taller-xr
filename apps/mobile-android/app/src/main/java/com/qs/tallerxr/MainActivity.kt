package com.qs.tallerxr

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.qs.tallerxr.diagnostics.ProcessExitDiagnostics
import com.qs.tallerxr.ui.AppViewModel
import com.qs.tallerxr.ui.QsApp
import com.qs.tallerxr.ui.QsTheme

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The branded start screen stays until the project list is ready, so the first thing the
        // operator sees is the app and not an empty frame.  The time limit keeps a slow storage
        // from ever holding the app hostage behind its logo.
        val startedAt = SystemClock.elapsedRealtime()
        installSplashScreen().setKeepOnScreenCondition {
            appViewModel.uiState.value.loading &&
                SystemClock.elapsedRealtime() - startedAt < MAXIMUM_SPLASH_MILLIS
        }
        super.onCreate(savedInstanceState)
        ProcessExitDiagnostics.installJavaCrashHandler(applicationContext)
        ProcessExitDiagnostics.recordState(applicationContext, "activity:create")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { QsTheme { QsApp() } }
    }

    override fun onResume() {
        super.onResume()
        ProcessExitDiagnostics.recordState(applicationContext, "activity:resume")
    }

    override fun onPause() {
        ProcessExitDiagnostics.recordState(applicationContext, "activity:pause")
        super.onPause()
        // Distinguish entering pause from completing all synchronous lifecycle observers.
        ProcessExitDiagnostics.recordState(applicationContext, "activity:paused")
    }

    override fun onStop() {
        super.onStop()
        ProcessExitDiagnostics.recordState(applicationContext, "activity:stopped")
    }

    private companion object {
        const val MAXIMUM_SPLASH_MILLIS = 2_500L
    }
}
