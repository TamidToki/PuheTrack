package com.puhetrack.app.batch

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ResultReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** Runs all Sherpa JNI work in the private :inference process. */
class OfflineInferenceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messenger by lazy { Messenger(IncomingHandler()) }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    @Suppress("DEPRECATION")
    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what != MSG_TRANSCRIBE) return super.handleMessage(message)
            message.data.classLoader = ResultReceiver::class.java.classLoader
            val receiver = message.data.getParcelable<ResultReceiver>(KEY_RECEIVER) ?: return
            val path = message.data.getString(KEY_PATH).orEmpty()
            scope.launch {
                val result = runCatching { SherpaBatch.transcribe(applicationContext, File(path)) }
                val payload = Bundle()
                result.onSuccess {
                    payload.putString(KEY_TRANSCRIPT, it.toJson())
                    receiver.send(RESULT_OK, payload)
                }.onFailure {
                    payload.putString(KEY_ERROR, it::class.java.simpleName)
                    receiver.send(RESULT_ERROR, payload)
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val MSG_TRANSCRIBE = 1
        const val RESULT_OK = 1
        const val RESULT_ERROR = 2
        const val KEY_RECEIVER = "receiver"
        const val KEY_PATH = "path"
        const val KEY_TRANSCRIPT = "transcript"
        const val KEY_ERROR = "error"
    }
}
