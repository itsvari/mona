package com.deliacheminot.mona.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ADB-only verification of the production inbox and headless Dart runtime. */
class WearDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.deliacheminot.mona.WEAR_DEBUG_SYNC") return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "mona-wear-debug", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WearDebugWorker>().build(),
        )
    }
}

class WearDebugWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val output = File(applicationContext.filesDir, "wear-debug-output.json")
        return try {
            val input = File(applicationContext.filesDir, "wear-debug-input.json")
            val commands = if (input.exists()) JSONArray(input.readText()) else JSONArray()
            val inbox = WearInbox(applicationContext)
            for (index in 0 until commands.length()) {
                val entry = commands.getJSONObject(index)
                val command = WearProtocol.command(entry.getString("path"), entry.getString("json"))
                    ?: error("Invalid debug command")
                inbox.put(command)
            }
            val pending = inbox.commands()
            val batch = PhoneWearRuntime.execute(applicationContext, pending)
            val result = JSONObject()
                .put("status", "complete")
                .put("snapshot", JSONObject(batch.snapshot))
                .put("results", JSONArray(batch.results.map { JSONObject(it.json) }))
                .put("processedPaths", JSONArray(batch.processedPaths.toList()))
            output.writeText(result.toString())
            inbox.complete(inbox.requested(), pending, batch.processedPaths)
            ForegroundWearBridge.refresh()
            Result.success()
        } catch (failure: Exception) {
            output.writeText(JSONObject().put("status", "failed").put("error", failure.javaClass.simpleName).toString())
            Result.failure()
        }
    }
}
