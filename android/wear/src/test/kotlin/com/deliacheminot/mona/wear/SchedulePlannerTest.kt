package com.deliacheminot.mona.wear

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SchedulePlannerTest {
    private val singapore = ZoneId.of("Asia/Singapore")
    private val morning = Instant.parse("2026-09-22T23:00:00Z")

    @Test fun logicalDayUsesEventTimezoneAndBoundary() {
        val before = Instant.parse("2026-09-22T19:59:59Z")
        val boundary = Instant.parse("2026-09-22T20:00:00Z")
        assertEquals(LocalDate.parse("2026-09-22"), SchedulePlanner.logicalDate(before, singapore, 240))
        assertEquals(LocalDate.parse("2026-09-23"), SchedulePlanner.logicalDate(boundary, singapore, 240))
        assertEquals(LocalDate.parse("2026-09-21"), SchedulePlanner.logicalDate(before, ZoneId.of("Pacific/Honolulu"), 600))
    }

    @Test fun earlyMorningTimesBelongToPreviousLogicalDay() {
        val rows = SchedulePlanner.occurrences(medication(times = listOf(120)), emptyList(),
            Instant.parse("2026-09-22T17:00:00Z"), singapore, 240, days = 1)
        assertEquals(LocalDate.parse("2026-09-22"), rows.single().date)
        assertEquals(Instant.parse("2026-09-22T18:00:00Z"), rows.single().at)
    }

    @Test fun dailyDoseLoggingSuppressesOnlyItsScheduledTime() {
        val m = medication(times = listOf(480, 1200), overrides = mapOf(1200 to BigDecimal("1.5")))
        val reminders = SchedulePlanner.nextReminders(snapshot(listOf(m)), listOf(intake()), morning, singapore)
        assertEquals(2, reminders.size)
        assertEquals(Instant.parse("2026-09-23T12:00:00Z"), reminders[0].at)
        assertEquals(BigDecimal("1.5"), reminders[0].dose)
        assertEquals(Instant.parse("2026-09-24T00:00:00Z"), reminders[1].at)
    }

    @Test fun fixedIntervalDoesNotDriftWhenAnIntakeIsLate() {
        val m = medication("intervalDays", start = "2026-09-01", intervalDays = 7)
        val rows = SchedulePlanner.occurrences(m, listOf(intake(date = "2026-09-10", at = "2026-09-10T00:00:00Z", minute = null)),
            morning, singapore, 0, days = 8)
        assertEquals(listOf(LocalDate.parse("2026-09-29")), rows.map { it.date })
    }

    @Test fun dynamicIntervalMovesFromLatestDoseIncludingPendingDose() {
        val m = medication("dynamicInterval", start = "2026-09-01", intervalDays = 7)
        val pending = PendingAction(command())
        val state = WatchState(snapshot(listOf(m)), listOf(pending), null, true)
        val reminder = SchedulePlanner.nextReminders(state.snapshot!!, state.history(), morning, singapore).single()
        assertEquals(LocalDate.parse("2026-09-30"), reminder.date)
    }

    @Test fun dynamicSplitDosesTrackIndependentIntakeDates() {
        val m = medication("dynamicInterval", times = listOf(480, 1200), start = "2026-09-01",
            intervalDays = 7, overrides = mapOf(1200 to BigDecimal("1")))
        val history = listOf(intake(date = "2026-09-20", at = "2026-09-20T00:00:00Z"),
            intake(date = "2026-09-21", minute = 1200, at = "2026-09-21T12:00:00Z", id = "101"))
        val reminders = SchedulePlanner.nextReminders(snapshot(listOf(m)), history, morning, singapore)
        assertEquals(listOf(LocalDate.parse("2026-09-27"), LocalDate.parse("2026-09-28")), reminders.map { it.date })
    }

    @Test fun weeklySchedulesRespectIsoWeekdaysAndFutureStart() {
        val m = medication("weekly", start = "2026-09-26", weekdays = listOf(1, 5))
        val rows = SchedulePlanner.occurrences(m, emptyList(), morning, singapore, 0, days = 8)
        assertEquals(listOf(LocalDate.parse("2026-09-28"), LocalDate.parse("2026-10-02")), rows.map { it.date })
    }

    @Test fun monthlyIntervalAnchorsToFirstOccurrenceAfterStart() {
        val m = medication("monthly", start = "2026-09-20", dayOfMonth = 15, intervalMonths = 2)
        val rows = SchedulePlanner.occurrences(m, emptyList(), morning, singapore, 0, days = 100)
        assertEquals(listOf(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-12-15")), rows.map { it.date })
    }

    @Test fun longestSupportedIntervalsStillHaveAnOfflineNextReminder() {
        val m = medication("intervalDays", start = "2026-09-23", intervalDays = 3650)
        val afterTime = Instant.parse("2026-09-23T01:00:00Z")
        val next = SchedulePlanner.nextReminders(snapshot(listOf(m)), emptyList(), afterTime, singapore).single()
        assertEquals(LocalDate.parse("2026-09-23").plusDays(3650), next.date)
    }

    @Test fun dstGapAndOverlapResolveToOneReminderPerLocalOccurrence() {
        val zone = ZoneId.of("America/New_York")
        val spring = SchedulePlanner.occurrences(medication(times = listOf(150), start = "2026-03-08"),
            emptyList(), Instant.parse("2026-03-08T05:00:00Z"), zone, 0, days = 1).single()
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), spring.at)
        val autumn = SchedulePlanner.occurrences(medication(times = listOf(90), start = "2026-11-01"),
            emptyList(), Instant.parse("2026-11-01T04:00:00Z"), zone, 0, days = 1).single()
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), autumn.at)
    }

    @Test fun deletedDisabledAndAsNeededSchedulesNeverCreateAlarms() {
        val m = medication("asNeeded", times = emptyList())
        assertTrue(SchedulePlanner.nextReminders(snapshot(listOf(m)), emptyList(), morning, singapore).isEmpty())
        assertTrue(SchedulePlanner.nextReminders(snapshot(enabled = false), emptyList(), morning, singapore).isEmpty())
        assertTrue(SchedulePlanner.nextReminders(snapshot(medications = emptyList()), emptyList(), morning, singapore).isEmpty())
        assertTrue(SchedulePlanner.nextReminders(snapshot(listOf(medication(notify = false))), emptyList(), morning, singapore).isEmpty())
    }

    @Test fun intervalRemindersAtSeveralTimesShareOneDoseIdentity() {
        val m = medication("intervalDays", times = listOf(480, 1200), start = "2026-09-23", intervalDays = 7)
        val occurrences = SchedulePlanner.occurrences(m, emptyList(), morning, singapore, 0, days = 1)
        assertEquals(occurrences[0].key, occurrences[1].key)
        assertNull(occurrences[0].scheduledMinute)
        assertTrue(occurrences.all { SchedulePlanner.taken(it, listOf(intake(minute = null))) })
    }
}
