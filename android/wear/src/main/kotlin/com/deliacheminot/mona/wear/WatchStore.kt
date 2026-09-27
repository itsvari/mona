package com.deliacheminot.mona.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal data class PendingAction(val command: String, val receipt: String? = null, val awaitingSnapshot: Boolean = false) {
    val data get() = JSONObject(command)
    val id get() = data.getString("id")
    val kind get() = data.getString("kind")
    val payload get() = data.getJSONObject("payload")
    val status get() = receipt?.let { JSONObject(it).getString("status") } ?: "pending"
    val message get() = receipt?.let { JSONObject(it).getString("message") }
    val path get() = "$DATA_ROOT/commands/${data.getString("installationId")}/$id"
}

internal data class WatchState(val snapshot: Snapshot?, val actions: List<PendingAction>, val error: String?, val remindersEnabled: Boolean) {
    val pending get() = actions.filter { it.status == "pending" }
    fun history(): List<Intake> {
        val s = snapshot ?: return emptyList()
        val overlay = actions.filter { it.status == "pending" || (it.status == "applied" && it.awaitingSnapshot) }.filter { it.kind == "recordDose" && it.data.getString("datasetId") == s.datasetId &&
            (it.receipt == null || s.history.none { intake -> intake.id == JSONObject(it.receipt).stringOrNull("entityId") }) }.mapNotNull { action ->
            val p = action.payload
            val medication = s.schedules.find { it.id == p.getString("scheduleId") } ?: return@mapNotNull null
            if (medication.revision != p.getString("scheduleRevision")) return@mapNotNull null
            val at = Instant.ofEpochMilli(p.getLong("at"))
            val zone = ZoneId.of(p.getString("zoneId"))
            val day = SchedulePlanner.logicalDate(at, zone, s.logicalDayStartMinutes)
            if (medication.recurrence.type != "asNeeded" && s.history.any { it.scheduleId == medication.id &&
                it.logicalDate == day && (!medication.hasSeparateDoses || it.scheduledMinute == p.intOrNull("scheduledMinute")) }) return@mapNotNull null
            Intake(action.id, medication.id, p.getString("dose").toBigDecimal(), medication.unit, at, zone.id,
                p.intOrNull("scheduledMinute"), day, true)
        }
        return s.history + overlay
    }
}

internal class WatchStore private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("mona_wear", Context.MODE_PRIVATE)
    val installationId: String = prefs.getString("installation", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("installation", it).commit())
    }
    private val mutableState = MutableStateFlow(readState())
    val state = mutableState.asStateFlow()

    private fun readState(): WatchState {
        val snapshot = prefs.getString("snapshot", null)?.let { runCatching { Snapshot.parse(it) }.getOrNull() }
        val actions = prefs.all.filterKeys { it.startsWith("command.") }.values.filterIsInstance<String>()
            .map { command ->
                val data = JSONObject(command)
                val id = data.getString("id")
                PendingAction(command, prefs.getString("receipt.$id", null),
                    data.getString("datasetId") == snapshot?.datasetId &&
                        prefs.getLong("receiptRevision.$id", -1) > (snapshot?.revision ?: 0))
            }
            .sortedByDescending { it.payload.optLong("at", 0) }
        return WatchState(snapshot, actions, prefs.getString("error", null), prefs.getBoolean("reminders", true))
    }

    @Synchronized fun acceptSnapshot(json: String) {
        val snapshot = Snapshot.parse(json)
        require(UUID.fromString(snapshot.datasetId).toString().equals(snapshot.datasetId, ignoreCase = true))
        require(snapshot.revision >= 0)
        val old = state.value.snapshot
        val retired = prefs.getStringSet("retiredDatasets", emptySet()).orEmpty()
        if (snapshot.datasetId in retired) return
        if (old?.datasetId == snapshot.datasetId && old.revision >= snapshot.revision) return
        val edit = prefs.edit().putString("snapshot", json).remove("error")
        if (old != null && old.datasetId != snapshot.datasetId) {
            edit.putStringSet("retiredDatasets", retired + old.datasetId)
        }
        for (action in state.value.pending) {
            if (action.data.getString("datasetId") != snapshot.datasetId) {
                edit.putString("receipt.${action.id}", JSONObject().put("version", 1).put("id", action.id)
                    .put("datasetId", action.data.getString("datasetId")).put("status", "rejected")
                    .put("entityId", JSONObject.NULL).put("message", "Mona's data was replaced. Review this action before logging again.").toString())
                edit.putString("cleanup.${action.id}", action.path)
                edit.remove("cleaned.${action.id}")
            }
        }
        commit(edit)
    }

    @Synchronized fun acceptReceipt(json: String) {
        val result = JSONObject(json)
        require(result.getInt("version") == 1 && result.getString("status") in listOf("applied", "rejected"))
        val id = result.getString("id")
        val action = state.value.actions.find { it.id == id } ?: return
        if (action.receipt == json) return
        if (result.getString("datasetId") != action.data.getString("datasetId")) return
        if (state.value.snapshot?.datasetId != action.data.getString("datasetId")) return
        commit(prefs.edit().putString("receipt.$id", json)
            .putLong("receiptRevision.$id", result.optLong("revision", (state.value.snapshot?.revision ?: 0) + 1))
            .putString("cleanup.$id", action.path).remove("cleaned.$id").remove("error"))
    }

    @Synchronized fun enqueue(kind: String, payload: JSONObject): String {
        val s = state.value.snapshot ?: kotlin.error("Connect to Mona on your phone first.")
        val id = UUID.randomUUID().toString()
        val json = JSONObject().put("version", 1).put("id", id).put("installationId", installationId)
            .put("datasetId", s.datasetId).put("kind", kind).put("payload", payload).toString()
        require(json.toByteArray().size <= 16 * 1024)
        commit(prefs.edit().putString("command.$id", json))
        WatchSyncWorker.enqueue(context)
        ReminderScheduler(context).reschedule()
        return id
    }

    @Synchronized fun recordDose(medication: Medication, dose: BigDecimal, at: Instant, minute: Int?, supplyId: String?, notes: String?): String {
        require(dose > BigDecimal.ZERO) { "Enter an amount greater than zero." }
        val s = state.value.snapshot ?: kotlin.error("Connect to Mona first.")
        val zone = ZoneId.systemDefault()
        val day = SchedulePlanner.logicalDate(at, zone, s.logicalDayStartMinutes)
        if (medication.recurrence.type != "asNeeded") {
            val duplicate = state.value.pending.firstOrNull { it.kind == "recordDose" &&
                it.payload.getString("scheduleId") == medication.id &&
                it.payload.getString("scheduleRevision") == medication.revision &&
                (!medication.hasSeparateDoses || it.payload.intOrNull("scheduledMinute") == minute) &&
                SchedulePlanner.logicalDate(Instant.ofEpochMilli(it.payload.getLong("at")),
                    ZoneId.of(it.payload.getString("zoneId")), s.logicalDayStartMinutes) == day }
            if (duplicate != null) return duplicate.id
        }
        return enqueue("recordDose", JSONObject().put("scheduleId", medication.id).put("scheduleRevision", medication.revision)
            .put("dose", dose.display()).put("at", at.toEpochMilli()).put("zoneId", zone.id)
            .put("scheduledMinute", minute ?: JSONObject.NULL).put("supplyId", supplyId ?: JSONObject.NULL)
            .put("notes", notes?.takeIf { it.isNotBlank() } ?: JSONObject.NULL))
    }

    @Synchronized fun setReminders(enabled: Boolean) {
        commit(prefs.edit().putBoolean("reminders", enabled))
        ReminderScheduler(context).reschedule()
    }
    @Synchronized fun error(message: String?) = commit(prefs.edit().putString("error", message))
    @Synchronized fun dismissResult(id: String) {
        val action = state.value.actions.find { it.id == id } ?: return
        if (action.status == "pending" || (action.status == "applied" && action.awaitingSnapshot)) return
        commit(prefs.edit().putString("cleanup.$id", action.path)
            .remove("command.$id").remove("receipt.$id").remove("receiptRevision.$id").remove("cleaned.$id"))
    }
    @Synchronized fun cleanupPaths(): Set<String> = prefs.all.filterKeys { it.startsWith("cleanup.") }
        .values.filterIsInstance<String>().toSet() + state.value.actions.filter {
            it.status != "pending" && !prefs.getBoolean("cleaned.${it.id}", false)
        }.map { it.path }

    @Synchronized fun completeCleanup(path: String) {
        val id = path.substringAfterLast('/')
        val edit = prefs.edit()
        if (prefs.getString("cleanup.$id", null) == path) edit.remove("cleanup.$id")
        if (state.value.actions.any { it.id == id && it.path == path && it.status != "pending" }) {
            edit.putBoolean("cleaned.$id", true)
        }
        commit(edit)
    }
    @Synchronized fun draft(): String? = prefs.getString("draft", null)
    @Synchronized fun saveDraft(json: String?) { check(prefs.edit().putString("draft", json).commit()) }
    private fun commit(edit: android.content.SharedPreferences.Editor) {
        check(edit.commit()) { "Could not save on this watch. Try again." }
        mutableState.value = readState()
    }
    companion object {
        @Volatile private var instance: WatchStore? = null
        fun get(context: Context): WatchStore = instance ?: synchronized(this) {
            instance ?: WatchStore(context.applicationContext).also { instance = it }
        }
    }
}
