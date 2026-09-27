package com.deliacheminot.mona.wear

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal data class MedicationDraft(
    val name: String = "", val moleculeName: String = "estradiol", val unit: String = "mg",
    val route: String = "oral", val ester: String? = null, val dose: String = "",
    val unitDose: String = "", val startDate: String = LocalDate.now().toString(),
    val type: String = "daily", val times: List<Int> = listOf(9 * 60),
    val intervalDays: Int = 7, val weekdays: List<Int> = listOf(1),
    val dayOfMonth: Int = 1, val intervalMonths: Int = 1, val notify: Boolean = true,
) {
    fun errors(): List<String> = buildList {
        if (name.isBlank()) add("Enter a medication name.")
        if (moleculeName.isBlank() || unit.isBlank()) add("Enter a molecule and its dose unit.")
        if (dose.toBigDecimalOrNull()?.signum() != 1) add("Enter a dose greater than zero.")
        if (unitDose.isNotBlank() && unitDose.toBigDecimalOrNull()?.signum() != 1) add("Unit strength must be greater than zero.")
        if (moleculeName.equals("estradiol", true) && route == "injection" && ester == null) add("Choose an estradiol ester.")
        if (type == "weekly" && weekdays.isEmpty()) add("Choose at least one weekday.")
        if (type != "asNeeded" && times.isEmpty()) add("Add a reminder time.")
    }
    fun payload(): JSONObject = JSONObject().put("name", name.trim()).put("moleculeName", moleculeName.trim())
        .put("unit", unit.trim()).put("route", route).put("ester", ester ?: JSONObject.NULL)
        .put("dose", dose).put("unitDose", unitDose.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("startDate", startDate).put("doseOverrides", JSONArray())
        .put("recurrence", JSONObject().put("type", type).put("times", JSONArray(if (type == "asNeeded") emptyList() else times.sorted()))
            .put("notify", notify && type != "asNeeded").put("intervalDays", intervalDays)
            .put("weekdays", JSONArray(weekdays)).put("dayOfMonth", dayOfMonth).put("intervalMonths", intervalMonths))
    companion object {
        fun restore(json: String?): MedicationDraft {
            if (json == null) return MedicationDraft()
            return runCatching {
                val o = JSONObject(json); val r = o.getJSONObject("recurrence")
                MedicationDraft(o.getString("name"), o.getString("moleculeName"), o.getString("unit"),
                    o.getString("route"), o.stringOrNull("ester"), o.getString("dose"), o.stringOrNull("unitDose") ?: "",
                    o.getString("startDate"), r.getString("type"), r.getJSONArray("times").ints(),
                    r.getInt("intervalDays"), r.getJSONArray("weekdays").ints(), r.getInt("dayOfMonth"),
                    r.getInt("intervalMonths"), r.getBoolean("notify"))
            }.getOrDefault(MedicationDraft())
        }
    }
}
