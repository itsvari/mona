package com.deliacheminot.mona.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** ADB-only adapter for exercising production persistence without a paired radio. */
class WearDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.deliacheminot.mona.WEAR_DEBUG_STATE") return
        val input = File(context.filesDir, "wear-debug-input.json")
        var requestId = ""
        val result = try {
            require(input.length() <= 8 * 1024 * 1024)
            val request = JSONObject(input.readText())
            requestId = request.getString("requestId")
            val store = WatchStore.get(context)
            request.optJSONObject("snapshot")?.let { store.acceptSnapshot(it.toString()) }
            request.optJSONArray("results")?.let { results ->
                for (i in 0 until results.length()) store.acceptReceipt(results.getJSONObject(i).toString())
            }
            val actionId = when {
                request.has("createMedication") -> store.enqueue("createMedication", request.getJSONObject("createMedication"))
                request.has("recordDose") -> {
                    val dose = request.getJSONObject("recordDose")
                    val medication = store.state.value.snapshot!!.schedules.single { it.id == dose.getString("scheduleId") }
                    store.recordDose(medication, dose.getString("dose").toBigDecimal(), Instant.ofEpochMilli(dose.getLong("at")),
                        dose.intOrNull("scheduledMinute"), null, "Wear verification")
                }
                else -> null
            }
            ReminderScheduler(context).reschedule()
            val state = store.state.value
            JSONObject().put("status", "complete").put("installationId", store.installationId)
                .put("actionId", actionId ?: JSONObject.NULL)
                .put("datasetId", state.snapshot?.datasetId ?: JSONObject.NULL)
                .put("revision", state.snapshot?.revision ?: 0)
                .put("actions", JSONArray(state.actions.map {
                    JSONObject().put("command", JSONObject(it.command)).put("status", it.status)
                        .put("awaitingSnapshot", it.awaitingSnapshot)
                        .put("receipt", it.receipt?.let(::JSONObject) ?: JSONObject.NULL)
                }))
        } catch (failure: Exception) {
            JSONObject().put("status", "failed").put("error", failure.javaClass.simpleName)
        }
        result.put("requestId", requestId)
        val output = File(context.filesDir, "wear-debug-output.json")
        val temporary = File(context.filesDir, "wear-debug-output.tmp")
        temporary.writeText(result.toString())
        check(temporary.renameTo(output))
    }
}
