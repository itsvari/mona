package com.deliacheminot.mona.wear

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal const val PROTOCOL_VERSION = 1
internal const val DATA_ROOT = "/mona/v1"

internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else getString(key)
internal fun JSONObject.intOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else getInt(key)
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }
internal fun BigDecimal.display(): String = stripTrailingZeros().toPlainString()

internal data class Recurrence(
    val type: String,
    val times: List<Int>,
    val notify: Boolean,
    val intervalDays: Int = 1,
    val weekdays: List<Int> = emptyList(),
    val dayOfMonth: Int = 1,
    val intervalMonths: Int = 1,
) {
    init {
        require(type in listOf("daily", "intervalDays", "dynamicInterval", "weekly", "monthly", "asNeeded"))
        require(times.all { it in 0..1439 } && times.distinct().size == times.size)
        require(intervalDays in 1..3650 && intervalMonths in 1..120)
        require(weekdays.all { it in 1..7 } && dayOfMonth in 1..28)
        require(type != "weekly" || weekdays.isNotEmpty())
        require(type != "daily" || times.isNotEmpty())
    }
    fun json() = JSONObject().put("type", type).put("times", JSONArray(times))
        .put("notify", notify).put("intervalDays", intervalDays)
        .put("weekdays", JSONArray(weekdays)).put("dayOfMonth", dayOfMonth)
        .put("intervalMonths", intervalMonths)
    companion object {
        fun parse(o: JSONObject) = Recurrence(o.getString("type"), o.getJSONArray("times").ints(),
            o.getBoolean("notify"), o.optInt("intervalDays", 1),
            o.optJSONArray("weekdays")?.ints().orEmpty(), o.optInt("dayOfMonth", 1), o.optInt("intervalMonths", 1))
    }
}

internal data class Medication(
    val id: String,
    val revision: String,
    val name: String,
    val dose: BigDecimal,
    val unitDose: BigDecimal?,
    val moleculeName: String,
    val unit: String,
    val route: String,
    val ester: String?,
    val startDate: LocalDate,
    val recurrence: Recurrence,
    val overrides: Map<Int, BigDecimal>,
) {
    val hasSeparateDoses get() = recurrence.type == "daily" || overrides.keys.any { it in recurrence.times }
    fun doseAt(minute: Int?) = overrides[minute] ?: dose
    fun payload() = JSONObject().put("name", name).put("dose", dose.display())
        .put("unitDose", unitDose?.display() ?: JSONObject.NULL).put("moleculeName", moleculeName)
        .put("unit", unit).put("route", route).put("ester", ester ?: JSONObject.NULL)
        .put("startDate", startDate.toString()).put("recurrence", recurrence.json())
        .put("doseOverrides", JSONArray(overrides.map { (minute, amount) ->
            JSONObject().put("minute", minute).put("dose", amount.display()) }))
    companion object {
        fun parse(o: JSONObject) = Medication(o.getString("id"), o.getString("revision"), o.getString("name"),
            o.getString("dose").toBigDecimal(), o.stringOrNull("unitDose")?.toBigDecimal(),
            o.getString("moleculeName"), o.getString("unit"), o.getString("route"), o.stringOrNull("ester"),
            LocalDate.parse(o.getString("startDate")), Recurrence.parse(o.getJSONObject("recurrence")),
            o.optJSONArray("doseOverrides")?.objects().orEmpty().associate { it.getInt("minute") to it.getString("dose").toBigDecimal() })
    }
}

internal data class Intake(
    val id: String, val scheduleId: String?, val dose: BigDecimal, val unit: String,
    val at: Instant, val zoneId: String, val scheduledMinute: Int?, val logicalDate: LocalDate,
    val pending: Boolean = false,
) {
    companion object {
        fun parse(o: JSONObject) = Intake(o.getString("id"), o.stringOrNull("scheduleId"),
            o.getString("dose").toBigDecimal(), o.getString("unit"), Instant.ofEpochMilli(o.getLong("at")),
            o.getString("zoneId"), o.intOrNull("scheduledMinute"), LocalDate.parse(o.getString("logicalDate")))
    }
}

internal data class Supply(val id: String, val name: String, val moleculeName: String, val route: String,
    val ester: String?, val remainingDose: BigDecimal, val unitDose: BigDecimal, val unit: String) {
    fun matches(m: Medication) = moleculeName == m.moleculeName && unit == m.unit && route == m.route && ester == m.ester
}

internal data class Molecule(val name: String, val unit: String)
internal data class Snapshot(
    val datasetId: String, val revision: Long, val generatedAt: Instant, val zoneId: String,
    val logicalDayStartMinutes: Int, val notificationsEnabled: Boolean,
    val schedules: List<Medication>, val history: List<Intake>, val supplies: List<Supply>, val molecules: List<Molecule>,
) {
    companion object {
        fun parse(json: String): Snapshot {
            val o = JSONObject(json)
            require(o.getInt("version") == PROTOCOL_VERSION) { "Update Mona on both devices to sync." }
            return Snapshot(o.getString("datasetId"), o.getLong("revision"), Instant.ofEpochMilli(o.getLong("generatedAt")),
                o.getString("zoneId"), o.getInt("logicalDayStartMinutes").also { require(it in 0..1439) },
                o.getBoolean("notificationsEnabled"), o.getJSONArray("schedules").objects().map(Medication::parse),
                o.getJSONArray("history").objects().map(Intake::parse),
                o.getJSONArray("supplies").objects().map { Supply(it.getString("id"), it.getString("name"),
                    it.getString("moleculeName"), it.getString("route"), it.stringOrNull("ester"),
                    it.getString("remainingDose").toBigDecimal(), it.getString("unitDose").toBigDecimal(), it.getString("unit")) },
                o.getJSONArray("molecules").objects().map { Molecule(it.getString("name"), it.getString("unit")) })
        }
    }
}

internal data class Occurrence(val medication: Medication, val date: LocalDate, val minute: Int?, val at: Instant) {
    val scheduledMinute get() = if (medication.hasSeparateDoses) minute else null
    val key get() = "${medication.id}/$date/${scheduledMinute ?: -1}"
    val dose get() = medication.doseAt(minute)
}

internal object SchedulePlanner {
    fun logicalDate(at: Instant, zone: ZoneId, dayStart: Int): LocalDate {
        val local = at.atZone(zone)
        return local.toLocalDate().minusDays(if (local.hour * 60 + local.minute < dayStart) 1 else 0)
    }

    fun taken(occurrence: Occurrence, history: List<Intake>): Boolean = history.any {
        it.scheduleId == occurrence.medication.id && it.logicalDate == occurrence.date &&
            (!occurrence.medication.hasSeparateDoses || it.scheduledMinute == occurrence.minute)
    }

    fun occurrences(m: Medication, history: List<Intake>, now: Instant, zone: ZoneId,
        dayStart: Int, days: Int = 35, countPerTime: Int = Int.MAX_VALUE): List<Occurrence> {
        if (m.recurrence.type == "asNeeded") return emptyList()
        val today = logicalDate(now, zone, dayStart)
        val first = maxOf(today, m.startDate)
        val times = m.recurrence.times.ifEmpty { listOf(12 * 60) }
        val result = mutableListOf<Occurrence>()
        for (minute in times) {
            val last = history.filter { it.scheduleId == m.id &&
                (!m.hasSeparateDoses || it.scheduledMinute == minute) }.maxByOrNull { it.at }?.logicalDate
            val dynamicDate = last?.plusDays(m.recurrence.intervalDays.toLong()) ?: m.startDate
            var count = 0
            if (m.recurrence.type == "dynamicInterval" && dynamicDate < first) {
                val calendar = dynamicDate.plusDays(if (minute < dayStart) 1 else 0)
                result += Occurrence(m, dynamicDate, minute,
                    calendar.atTime(minute / 60, minute % 60).atZone(zone).toInstant())
            }
            for (offset in 0 until days) {
                val date = first.plusDays(offset.toLong())
                val scheduled = when (m.recurrence.type) {
                    "daily" -> true
                    "intervalDays" -> java.time.temporal.ChronoUnit.DAYS.between(m.startDate, date) % m.recurrence.intervalDays == 0L
                    "dynamicInterval" -> date == dynamicDate
                    "weekly" -> date.dayOfWeek.value in m.recurrence.weekdays
                    "monthly" -> {
                        val startMonth = m.startDate.withDayOfMonth(m.recurrence.dayOfMonth).let {
                            if (it < m.startDate) it.plusMonths(1) else it }
                        date >= startMonth && date.dayOfMonth == m.recurrence.dayOfMonth &&
                            java.time.temporal.ChronoUnit.MONTHS.between(startMonth.withDayOfMonth(1), date.withDayOfMonth(1)) % m.recurrence.intervalMonths == 0L
                    }
                    else -> false
                }
                if (!scheduled) continue
                val calendarDate = date.plusDays(if (minute < dayStart) 1 else 0)
                result += Occurrence(m, date, minute, calendarDate.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant())
                count += 1
                if (count >= countPerTime) break
            }
        }
        return result.sortedBy { it.at }
    }

    fun nextReminders(snapshot: Snapshot, history: List<Intake>, now: Instant, zone: ZoneId): List<Occurrence> {
        if (!snapshot.notificationsEnabled) return emptyList()
        return snapshot.schedules.filter { it.recurrence.notify && it.recurrence.times.isNotEmpty() }.flatMap { m ->
            occurrences(m, history, now, zone, snapshot.logicalDayStartMinutes, 3662, 2)
                .filter { it.at > now && !taken(it, history) }
                .groupBy { it.minute }.values.mapNotNull { it.firstOrNull() }
        }.sortedBy { it.at }
    }
}
