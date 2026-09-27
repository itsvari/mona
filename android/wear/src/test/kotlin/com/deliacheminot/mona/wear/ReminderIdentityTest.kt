package com.deliacheminot.mona.wear

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReminderIdentityTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private val medication = medication()
    private val occurrence = Occurrence(medication, LocalDate.parse("2026-09-23"), 480, Instant.parse("2026-09-23T00:00:00Z"))
    private val identity = ReminderIdentity.from(TEST_DATASET, occurrence)
    private fun state(snapshot: Snapshot = snapshot(listOf(medication))) = WatchState(snapshot, emptyList(), null, true)

    @Test fun reminderIdentityRoundTripsAndRemainsValidAfterItsFireTime() {
        assertEquals(identity, ReminderIdentity.parse(identity.key))
        assertEquals(occurrence, identity.occurrence(state(), zone))
    }

    @Test fun changedDoseRevisionInvalidatesAnAlreadyQueuedAlarm() {
        val revised = medication.copy(dose = BigDecimal("4"), revision = "changed")
        assertNull(identity.occurrence(state(snapshot(listOf(revised))), zone))
        assertNotEquals(identity.key, ReminderIdentity.from(TEST_DATASET, occurrence.copy(medication = revised)).key)
    }

    @Test fun datasetReplacementAndScheduleDeletionInvalidatePostedReminder() {
        assertNull(identity.occurrence(state(snapshot(dataset = "new-dataset")), zone))
        assertNull(identity.occurrence(state(snapshot(medications = emptyList())), zone))
    }

    @Test fun confirmedOrPendingIntakeDismissesPostedReminder() {
        assertNull(identity.occurrence(state(snapshot(history = listOf(intake()))), zone))
        val pendingState = WatchState(snapshot(), listOf(PendingAction(command())), null, true)
        assertNull(identity.occurrence(pendingState, zone))
    }

    @Test fun disabledNotificationsAndChangedTimezoneInvalidateOldAlarm() {
        assertNull(identity.occurrence(state(snapshot(enabled = false)), zone))
        assertNull(identity.occurrence(state().copy(remindersEnabled = false), zone))
        assertNull(identity.occurrence(state(), ZoneId.of("UTC")))
        val disabled = medication.copy(recurrence = medication.recurrence.copy(notify = false))
        assertNull(identity.occurrence(state(snapshot(listOf(disabled))), zone))
    }

    @Test fun malformedAndLegacyAlarmIdentitiesCannotShowNewMedicationData() {
        assertNull(ReminderIdentity.parse("42/2026-09-23/480/480"))
        assertNull(ReminderIdentity.parse(identity.key.replace("/480/480/", "/480/1440/")))
        assertNull(ReminderIdentity.parse(identity.key.replace("2026-09-23", "invalid")))
    }
}
