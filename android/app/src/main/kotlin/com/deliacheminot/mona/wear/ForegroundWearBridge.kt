package com.deliacheminot.mona.wear

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ForegroundWearBridge(context: Context, messenger: BinaryMessenger) {
    private val context = context.applicationContext
    private val channel = MethodChannel(messenger, WearProtocol.CHANNEL)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val importOwner = Any()

    init {
        foreground = this
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "requestSync" -> {
                    WearSyncWorker.request(context)
                    result.success(null)
                }
                "beginImport" -> scope.launch {
                    try {
                        if (!PhoneWearRuntime.lifetime.holdsLock(importOwner)) {
                            withTimeout(120_000) { PhoneWearRuntime.lifetime.lock(importOwner) }
                        }
                        result.success(null)
                    } catch (_: Exception) {
                        result.error("wear_busy", "Wait for watch synchronization and try again", null)
                    }
                }
                "endImport" -> {
                    releaseImport()
                    WearSyncWorker.request(context)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
        WearSyncWorker.request(context)
    }

    private fun releaseImport() {
        if (PhoneWearRuntime.lifetime.holdsLock(importOwner)) {
            PhoneWearRuntime.lifetime.unlock(importOwner)
        }
    }

    fun close() {
        if (foreground === this) foreground = null
        channel.setMethodCallHandler(null)
        scope.cancel()
        releaseImport()
    }

    companion object {
        private var foreground: ForegroundWearBridge? = null

        internal suspend fun refresh() {
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                foreground?.channel?.invokeMethod("refresh", null)
            }
        }
    }
}
