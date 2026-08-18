package com.robonix.client

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.robonix.client.ui.navigation.AppNavigation
import com.robonix.client.ui.theme.Bg
import com.robonix.client.ui.theme.RobonixTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.write("UI", "MainActivity onCreate")

        // Global uncaught exception handler — ensures even compose rendering
        // crashes are logged before the process dies.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("RobonixCrash", "FATAL on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        enableEdgeToEdge()
        setContent {
            AppLog.write("UI", "setContent composing")
            RobonixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Bg,
                ) {
                    AppNavigation()
                }
            }
        }
    }
}
