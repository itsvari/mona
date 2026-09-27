package com.deliacheminot.mona.wear

import android.content.Context
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal object PhoneWearRuntime {
    val lifetime = Mutex()

    suspend fun execute(context: Context, commands: List<WearCommand>): WearBatch =
        lifetime.withLock {
            withContext(Dispatchers.Main) {
                val loader = FlutterInjector.instance().flutterLoader()
                loader.startInitialization(context.applicationContext)
                loader.ensureInitializationComplete(context.applicationContext, null)
                val engine = FlutterEngine(context.applicationContext)
                val completion = CompletableDeferred<WearBatch>()
                val channel = MethodChannel(engine.dartExecutor.binaryMessenger, WearProtocol.CHANNEL)
                channel.setMethodCallHandler { call, result ->
                    when (call.method) {
                        "loadCommands" -> result.success(commands.map { mapOf("path" to it.path, "json" to it.json) })
                        "complete" -> {
                            try {
                                val batch = WearProtocol.batch(call.arguments, commands)
                                result.success(null)
                                completion.complete(batch)
                            } catch (_: Exception) {
                                result.error("invalid_completion", "Invalid Wear synchronization result", null)
                                completion.completeExceptionally(IllegalStateException("Invalid synchronization result"))
                            }
                        }
                        "failed" -> {
                            result.success(null)
                            completion.completeExceptionally(IllegalStateException("Wear synchronization needs retry"))
                        }
                        else -> result.notImplemented()
                    }
                }
                try {
                    engine.dartExecutor.executeDartEntrypoint(
                        DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "wearBackgroundMain"),
                    )
                    withTimeout(90_000) { completion.await() }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        channel.setMethodCallHandler(null)
                        engine.destroy()
                    }
                }
            }
        }
}
