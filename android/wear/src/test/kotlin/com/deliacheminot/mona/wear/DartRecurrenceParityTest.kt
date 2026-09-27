package com.deliacheminot.mona.wear

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

class DartRecurrenceParityTest {
    @Test fun offlineRemindersMatchThePhonePlannerFixtures() {
        val text = javaClass.classLoader!!.getResourceAsStream("recurrence-fixtures.json")!!.bufferedReader().use { it.readText() }
        val fixtures = JSONArray(text).objects()
        for (fixture in fixtures) {
            val name = fixture.getString("name")
            val snapshot = Snapshot.parse(fixture.getJSONObject("snapshot").toString())
            val actual = SchedulePlanner.nextReminders(snapshot, snapshot.history,
                Instant.ofEpochMilli(fixture.getLong("now")), ZoneId.of(fixture.getString("zoneId")))
            val expected = fixture.getJSONArray("nextReminders").objects()
            assertEquals("$name reminder count", expected.size, actual.size)
            expected.zip(actual).forEach { (phone, watch) ->
                assertEquals("$name minute", phone.getInt("minute"), watch.minute)
                assertEquals("$name instant", phone.getLong("at"), watch.at.toEpochMilli())
                assertEquals("$name dose", 0, BigDecimal(phone.getString("dose")).compareTo(watch.dose))
            }
        }
    }
}
