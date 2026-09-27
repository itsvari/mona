package com.deliacheminot.mona.wear

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

internal const val TEST_DATASET = "7a5e2e78-a21b-445b-8ee6-f4d2b2e73372"
internal const val TEST_INSTALLATION = "b3353202-50ca-4e35-ad8a-a940f999fd36"
internal const val TEST_OPERATION = "d3e2824a-97d6-4a3f-8421-4c930280a74d"

internal fun medication(
    type: String = "daily",
    times: List<Int> = listOf(8 * 60),
    start: String = "2026-01-01",
    intervalDays: Int = 1,
    weekdays: List<Int> = emptyList(),
    dayOfMonth: Int = 1,
    intervalMonths: Int = 1,
    overrides: Map<Int, BigDecimal> = emptyMap(),
    notify: Boolean = true,
    id: String = "42",
) = Medication(id, "revision", "Estradiol", BigDecimal("2"), null, "Estradiol", "mg",
    "oral", null, LocalDate.parse(start),
    Recurrence(type, times, notify, intervalDays, weekdays, dayOfMonth, intervalMonths), overrides)

internal fun intake(
    date: String = "2026-09-23",
    minute: Int? = 480,
    at: String = "2026-09-23T00:00:00Z",
    id: String = "100",
    scheduleId: String = "42",
) = Intake(id, scheduleId, BigDecimal("2"), "mg", Instant.parse(at), "Asia/Singapore", minute, LocalDate.parse(date))

internal fun snapshot(
    medications: List<Medication> = listOf(medication()),
    history: List<Intake> = emptyList(),
    revision: Long = 1,
    dataset: String = TEST_DATASET,
    dayStart: Int = 0,
    enabled: Boolean = true,
) = Snapshot(dataset, revision, Instant.parse("2026-09-23T00:00:00Z"), "Asia/Singapore",
    dayStart, enabled, medications, history, emptyList(), listOf(Molecule("Estradiol", "mg")))

internal fun Snapshot.json(): String = JSONObject().put("version", 1).put("datasetId", datasetId)
    .put("revision", revision).put("generatedAt", generatedAt.toEpochMilli()).put("zoneId", zoneId)
    .put("logicalDayStartMinutes", logicalDayStartMinutes).put("notificationsEnabled", notificationsEnabled)
    .put("schedules", JSONArray(schedules.map { it.payload().put("id", it.id).put("revision", it.revision) }))
    .put("history", JSONArray(history.map { item -> JSONObject().put("id", item.id)
        .put("scheduleId", item.scheduleId ?: JSONObject.NULL).put("dose", item.dose.toPlainString())
        .put("unit", item.unit).put("at", item.at.toEpochMilli()).put("zoneId", item.zoneId)
        .put("scheduledMinute", item.scheduledMinute ?: JSONObject.NULL).put("logicalDate", item.logicalDate.toString()) }))
    .put("supplies", JSONArray()).put("molecules", JSONArray(molecules.map { JSONObject().put("name", it.name).put("unit", it.unit) }))
    .toString()

internal fun command(dataset: String = TEST_DATASET): String = JSONObject().put("version", 1)
    .put("datasetId", dataset).put("id", TEST_OPERATION).put("installationId", TEST_INSTALLATION)
    .put("kind", "recordDose").put("payload", JSONObject().put("scheduleId", "42")
        .put("scheduleRevision", "revision").put("dose", "2")
        .put("at", Instant.parse("2026-09-23T00:00:00Z").toEpochMilli()).put("zoneId", "Asia/Singapore")
        .put("scheduledMinute", 480).put("supplyId", JSONObject.NULL).put("notes", JSONObject.NULL)).toString()

internal fun receipt(status: String = "applied", dataset: String = TEST_DATASET): String = JSONObject()
    .put("version", 1).put("id", TEST_OPERATION).put("datasetId", dataset).put("status", status)
    .put("revision", 2)
    .put("entityId", if (status == "applied") "100" else JSONObject.NULL)
    .put("message", if (status == "applied") "Recorded in Mona" else "Schedule changed").toString()
