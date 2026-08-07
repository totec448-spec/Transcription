package com.example.transcription.recording

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.transcription.AppContainer
import com.example.transcription.MainActivity

class RecordingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppContainer.initialize(context)
        val action = intent.action ?: return
        if (action == ACTION_RESTORE_NOTIFICATION) {
            val result = goAsync()
            Handler(Looper.getMainLooper()).postDelayed({
                NotificationHelper.restoreCurrent(context.applicationContext)
                result.finish()
            }, 250L)
            return
        }
        if (action == RecordingService.ACTION_START &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_REQUEST_RECORDING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            return
        }
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(action))
    }

    companion object {
        const val ACTION_RESTORE_NOTIFICATION = "com.example.transcription.RESTORE_NOTIFICATION"
    }
}
