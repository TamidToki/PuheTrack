package com.puhetrack.app.batch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.ResultReceiver
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OfflineInferenceClient {
    suspend fun transcribe(context: Context, wavFile: File): BatchTranscript =
        suspendCancellableCoroutine { continuation ->
            val app = context.applicationContext
            val finished = AtomicBoolean(false)
            lateinit var connection: ServiceConnection

            fun unbind() {
                runCatching { app.unbindService(connection) }
            }

            fun succeed(value: BatchTranscript) {
                if (finished.compareAndSet(false, true)) {
                    unbind()
                    continuation.resume(value)
                }
            }

            fun fail(error: Throwable) {
                if (finished.compareAndSet(false, true)) {
                    unbind()
                    continuation.resumeWithException(error)
                }
            }

            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == OfflineInferenceService.RESULT_OK) {
                        val json = resultData?.getString(OfflineInferenceService.KEY_TRANSCRIPT)
                        if (json.isNullOrBlank()) fail(IllegalStateException("Inference returned no transcript"))
                        else runCatching { BatchTranscript.fromJson(json) }.onSuccess(::succeed).onFailure(::fail)
                    } else {
                        fail(IllegalStateException("Offline inference failed safely"))
                    }
                }
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    try {
                        val message = Message.obtain(null, OfflineInferenceService.MSG_TRANSCRIBE).apply {
                            data = Bundle().apply {
                                putParcelable(OfflineInferenceService.KEY_RECEIVER, receiver)
                                putString(OfflineInferenceService.KEY_PATH, wavFile.absolutePath)
                            }
                        }
                        Messenger(binder).send(message)
                    } catch (e: RemoteException) {
                        fail(e)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    fail(IllegalStateException("Offline inference process stopped"))
                }

                override fun onBindingDied(name: ComponentName?) {
                    fail(IllegalStateException("Offline inference process died"))
                }

                override fun onNullBinding(name: ComponentName?) {
                    fail(IllegalStateException("Offline inference service unavailable"))
                }
            }

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) unbind()
            }

            val bound = runCatching {
                app.bindService(
                    Intent(app, OfflineInferenceService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }.getOrDefault(false)
            if (!bound) fail(IllegalStateException("Could not start offline inference"))
        }
}
