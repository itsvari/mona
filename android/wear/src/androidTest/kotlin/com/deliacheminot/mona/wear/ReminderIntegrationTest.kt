package com.deliacheminot.mona.wear

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run on a dedicated watch emulator. Original preferences and notifications are restored. */
@RunWith(AndroidJUnit4::class)
class ReminderIntegrationTest {
    private lateinit var context: Context
    private lateinit var store: WatchStore
    private lateinit var notifications: NotificationManager
    private lateinit var savedPreferences: Map<String, Map<String, *>>
    private var savedNotifications = emptyArray<android.service.notification.StatusBarNotification>()
    private val createdPaths = mutableListOf<String>()
    private val postedTags = mutableSetOf<String>()
    private val dataset = UUID.randomUUID().toString()
    private lateinit var medication: Medication

    @Before fun prepare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext.applicationContext
        notifications = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        stopSync()
        savedPreferences = listOf("mona_wear", "mona_alarms").associateWith {
            context.getSharedPreferences(it, 0).all.toMap()
        }
        savedNotifications = notifications.activeNotifications.filter { it.notification.channelId == ReminderScheduler.CHANNEL }.toTypedArray()
        WatchStore.get(context).setReminders(false)
        savedPreferences.keys.forEach { assertTrue(context.getSharedPreferences(it, 0).edit().clear().commit()) }
        resetStore()
        store = WatchStore.get(context)
        val local = Instant.now().atZone(ZoneId.systemDefault())
        val minute = local.hour * 60 + local.minute
        medication = Medication("72001", "integration-v1", "Integration medication", BigDecimal("2"), null,
            "estradiol", "mg", "oral", null, local.toLocalDate(), Recurrence("daily", listOf(minute), true), emptyMap())
        store.acceptSnapshot(snapshotJson(medication, 1))
        ReminderScheduler(context).reschedule()
    }

    @After fun restore() {
        if (!::context.isInitialized) return
        stopSync()
        for (tag in postedTags) notifications.cancel(tag, 1)
        runCatching {
            val node = Tasks.await(Wearable.getNodeClient(context).localNode, 5, TimeUnit.SECONDS).id
            for (path in createdPaths) Tasks.await(Wearable.getDataClient(context).deleteDataItems(
                android.net.Uri.Builder().scheme("wear").authority(node).path(path).build()), 5, TimeUnit.SECONDS)
        }
        if (::savedPreferences.isInitialized) {
            ReminderScheduler(context).reschedule()
            WatchStore.get(context).setReminders(false)
            savedPreferences.forEach { (name, values) ->
                val editor = context.getSharedPreferences(name, 0).edit().clear()
                values.forEach { (key, value) -> editor.restoreValue(key, value) }
                assertTrue(editor.commit())
            }
            resetStore()
            ReminderScheduler(context).reschedule()
            for (original in savedNotifications) notifications.notify(original.tag, original.id, original.notification)
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("mona-periodic-sync", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WatchSyncWorker>(6, TimeUnit.HOURS).setInputData(workDataOf("refresh" to true)).build())
    }

    @Test fun firingReminderPersistsUntilDoseIsLoggedAndNextAlarmIsArmed() {
        val identity = currentReminder()
        // Reproduce AlarmManager delivering the currently persisted occurrence.
        context.getSharedPreferences("mona_alarms", 0).edit().putStringSet("keys", setOf(identity.key)).commit()
        deliverAlarm(identity.key)
        assertTrue(notifications.activeNotifications.any { it.tag == identity.key })
        val next = context.getSharedPreferences("mona_alarms", 0).getStringSet("keys", emptySet()).orEmpty()
        assertTrue(next.isNotEmpty())
        assertTrue(next.all { requireNotNull(ReminderIdentity.parse(it)).at > Instant.now() })

        val id = store.recordDose(medication, medication.dose, Instant.now(), identity.minute, null, null)
        createdPaths += store.state.value.actions.single { it.id == id }.path
        assertFalse(notifications.activeNotifications.any { it.tag == identity.key })
        assertTrue(context.getSharedPreferences("mona_alarms", 0).getStringSet("keys", emptySet()).orEmpty().isNotEmpty())
        assertTrue(File(context.applicationInfo.dataDir, "shared_prefs/mona_wear.xml").readText().contains(id))
    }

    @Test fun changedRevisionDismissesPostedReminderAndRejectsOldInFlightAlarm() {
        val old = currentReminder()
        deliverAlarm(old.key)
        assertTrue(notifications.activeNotifications.any { it.tag == old.key })
        medication = medication.copy(revision = "integration-v2", dose = BigDecimal("4"))
        store.acceptSnapshot(snapshotJson(medication, 2))
        ReminderScheduler(context).reschedule()
        assertFalse(notifications.activeNotifications.any { it.tag == old.key })
        deliverAlarm(old.key)
        assertFalse(notifications.activeNotifications.any { it.tag == old.key })
        assertTrue(context.getSharedPreferences("mona_alarms", 0).getStringSet("keys", emptySet()).orEmpty()
            .all { ReminderIdentity.parse(it)?.revision == "integration-v2" })
    }

    @Test fun productionOutboxSurvivesReloadAndRepeatedTapKeepsOneOperation() {
        val at = Instant.now()
        val minute = medication.recurrence.times.single()
        val id = store.recordDose(medication, medication.dose, at, minute, null, null)
        createdPaths += store.state.value.actions.single { it.id == id }.path
        stopSync()
        resetStore()
        store = WatchStore.get(context)
        assertEquals(id, store.state.value.pending.single().id)
        assertEquals(id, store.recordDose(medication, medication.dose, at, minute, null, null))
        assertEquals(1, store.state.value.pending.size)
        assertTrue(File(context.applicationInfo.dataDir, "shared_prefs/mona_wear.xml").readText().contains(id))
    }

    @Test fun unrelatedBroadcastDoesNotScheduleMedicationAlarms() {
        context.getSharedPreferences("mona_alarms", 0).edit().remove("keys").commit()
        RescheduleReceiver().onReceive(context, Intent("com.example.UNRELATED"))
        assertTrue(context.getSharedPreferences("mona_alarms", 0).getStringSet("keys", emptySet()).orEmpty().isEmpty())
    }

    private fun currentReminder(): ReminderIdentity {
        val minute = medication.recurrence.times.single()
        val date = LocalDate.now(ZoneId.systemDefault())
        return ReminderIdentity.from(dataset, Occurrence(medication, date, minute,
            date.atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant()))
    }

    private fun deliverAlarm(key: String) {
        postedTags += key
        val finished = CountDownLatch(1)
        context.sendOrderedBroadcast(Intent(context, ReminderReceiver::class.java).putExtra("key", key), null,
            object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { finished.countDown() } },
            Handler(Looper.getMainLooper()), 0, null, null)
        assertTrue("Alarm receiver did not finish", finished.await(10, TimeUnit.SECONDS))
    }

    private fun stopSync() {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork("mona-sync").result.get(10, TimeUnit.SECONDS)
        work.cancelUniqueWork("mona-periodic-sync").result.get(10, TimeUnit.SECONDS)
    }

    private fun resetStore() {
        WatchStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun snapshotJson(medication: Medication, revision: Long): String = JSONObject()
        .put("version", 1).put("datasetId", dataset).put("revision", revision).put("generatedAt", Instant.now().toEpochMilli())
        .put("zoneId", ZoneId.systemDefault().id).put("logicalDayStartMinutes", 0).put("notificationsEnabled", true)
        .put("schedules", JSONArray().put(medication.payload().put("id", medication.id).put("revision", medication.revision)))
        .put("history", JSONArray()).put("supplies", JSONArray()).put("molecules", JSONArray()).toString()

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.restoreValue(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
        }
    }
}
