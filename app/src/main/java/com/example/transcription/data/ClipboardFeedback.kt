package com.example.transcription.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** One clipboard path for manual and automatic copy, with a replace-not-queue confirmation. */
object ClipboardFeedback {
    private val main = Handler(Looper.getMainLooper())
    private var toast: Toast? = null
    private var appContext: Context? = null
    private val showConfirmation = Runnable {
        val context = appContext ?: return@Runnable
        toast?.cancel()
        toast = Toast.makeText(context, "Note copied", Toast.LENGTH_SHORT).also(Toast::show)
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Transcript", text))
        appContext = context.applicationContext
        main.removeCallbacks(showConfirmation)
        main.post(showConfirmation)
    }
}
