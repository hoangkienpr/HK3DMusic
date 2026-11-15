package com.hk.app

import android.app.Activity
import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_PREV -> pipActionHandler?.invoke(PIP_PREV)
                ACTION_PIP_TOGGLE -> pipActionHandler?.invoke(PIP_TOGGLE)
                ACTION_PIP_NEXT -> pipActionHandler?.invoke(PIP_NEXT)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Set system status bar and navigation bar to pitch black
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        registerPipReceiver()

        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MusicPlayerApp()
                }
            }
        }
    }

    private fun registerPipReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_PREV)
            addAction(ACTION_PIP_TOGGLE)
            addAction(ACTION_PIP_NEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pipReceiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        super.onDestroy()
    }

    // Entering Picture-in-Picture through onUserLeaveHint trapped the activity in
    // a zero-size invisible PiP surface on some devices. Keep manual PiP only —
    // triggered by a direct button in Full Player.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
    }

    companion object {
        const val ACTION_PIP_PREV = "com.hk.app.PIP_PREV"
        const val ACTION_PIP_TOGGLE = "com.hk.app.PIP_TOGGLE"
        const val ACTION_PIP_NEXT = "com.hk.app.PIP_NEXT"

        const val PIP_PREV = 1
        const val PIP_TOGGLE = 2
        const val PIP_NEXT = 3

        /** Compose side installs this handler; invoked on the main thread. */
        @Volatile
        var pipActionHandler: ((Int) -> Unit)? = null

        /** Picture-in-Picture params with aspect ratio, title and (API 31+) system action buttons. */
        fun buildPipParams(context: Context): android.app.PictureInPictureParams {
            val builder = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(1, 1))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // System-level action buttons on the PiP window edge (Android 12+)
                val prevIntent = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_PIP_PREV).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val toggleIntent = PendingIntent.getBroadcast(
                    context, 1,
                    Intent(ACTION_PIP_TOGGLE).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val nextIntent = PendingIntent.getBroadcast(
                    context, 2,
                    Intent(ACTION_PIP_NEXT).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val actions = listOf(
                    RemoteAction(
                        Icon.createWithResource(context, R.drawable.pip_prev),
                        "Trước", "Bài trước", prevIntent
                    ),
                    RemoteAction(
                        Icon.createWithResource(context, R.drawable.pip_play),
                        "Play/Pause", "Phát hoặc tạm dừng", toggleIntent
                    ),
                    RemoteAction(
                        Icon.createWithResource(context, R.drawable.pip_next),
                        "Tiếp", "Bài tiếp theo", nextIntent
                    )
                )
                builder.setActions(actions)
            }
            return builder.build()
        }
    }
}
