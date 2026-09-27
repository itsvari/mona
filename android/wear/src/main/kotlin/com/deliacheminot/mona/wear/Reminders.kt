package com.deliacheminot.mona.wear

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.phone.interactions.notifications.BridgingConfig
import androidx.wear.phone.interactions.notifications.BridgingManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class ReminderIdentity(
    val datasetId: String,
    val scheduleId: String,
    val date: LocalDate,
    val scheduledMinute: Int?,
    val minute: Int,
    val revision: String,
    val at: Instant,
) {
    val key get() = "$datasetId/$scheduleId/$date/${scheduledMinute ?: -1}/$minute/$revision/${at.toEpochMilli()}"

    fun occurrence(state: WatchState, zone: ZoneId): Occurrence? {
        val snapshot = state.snapshot ?: return null
        if (snapshot.datasetId != datasetId || !state.remindersEnabled || !snapshot.notificationsEnabled) return null
        val medication = snapshot.schedules.find { it.id == scheduleId } ?: return null
        if (medication.revision != revision || !medication.recurrence.notify || minute !in medication.recurrence.times) return null
        if (scheduledMinute != if (medication.hasSeparateDoses) minute else null) return null
        val calendarDate = date.plusDays(if (minute < snapshot.logicalDayStartMinutes) 1 else 0)
        if (calendarDate.atTime(minute / 60, minute % 60).atZone(zone).toInstant() != at) return null
        val occurrence = Occurrence(medication, date, minute, at)
        return occurrence.takeUnless { SchedulePlanner.taken(it, state.history()) }
    }

    companion object {
        fun from(datasetId: String, occurrence: Occurrence) = ReminderIdentity(datasetId,
            occurrence.medication.id, occurrence.date, occurrence.scheduledMinute,
            requireNotNull(occurrence.minute), occurrence.medication.revision, occurrence.at)

        fun parse(key: String): ReminderIdentity? = runCatching {
            val parts = key.split('/')
            require(parts.size == 7)
            val minute = parts[4].toInt()
            val scheduled = parts[3].toInt()
            require(minute in 0..1439 && scheduled in -1..1439)
            require(parts[0].isNotEmpty() && parts[1].isNotEmpty() && parts[5].isNotEmpty())
            ReminderIdentity(parts[0], parts[1], LocalDate.parse(parts[2]),
                scheduled.takeUnless { it == -1 }, minute, parts[5], Instant.ofEpochMilli(parts[6].toLong()))
        }.getOrNull()
    }
}

internal class ReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("mona_alarms", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    fun hasPermission(): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun isExact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun reschedule() = synchronized(lock) {
        val state = WatchStore.get(context).state.value
        val enabled = state.remindersEnabled && state.snapshot?.notificationsEnabled == true && hasPermission()
        // Bridging is restored when local reminders cannot run, including denied permissions.
        BridgingManager.fromContext(context).setConfig(BridgingConfig.Builder(context, !enabled).build())
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Medication reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders from your Mona medication schedules"
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
        val now = Instant.now()
        val next = if (enabled) state.snapshot?.let {
            SchedulePlanner.nextReminders(it, state.history(), now, ZoneId.systemDefault()) }.orEmpty() else emptyList()
        val keys = next.map { ReminderIdentity.from(requireNotNull(state.snapshot).datasetId, it).key }.toSet()
        val previous = prefs.getStringSet("keys", emptySet()).orEmpty()
        for (key in previous - keys) {
            alarms.cancel(alarmIntent(key))
        }
        // Already posted occurrences also need dismissal when a phone or watch intake arrives.
        val posted = prefs.getStringSet("posted", emptySet()).orEmpty().filterTo(mutableSetOf()) { key ->
            val keep = enabled && ReminderIdentity.parse(key)?.occurrence(state, ZoneId.systemDefault()) != null
            if (!keep) NotificationManagerCompat.from(context).cancel(key, 1)
            keep
        }
        for (occurrence in next) {
            val intent = alarmIntent(ReminderIdentity.from(requireNotNull(state.snapshot).datasetId, occurrence).key)
            if (isExact()) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, occurrence.at.toEpochMilli(), intent)
                } catch (_: SecurityException) {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, occurrence.at.toEpochMilli(), intent)
                }
            } else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, occurrence.at.toEpochMilli(), intent)
        }
        prefs.edit().putStringSet("keys", keys).putStringSet("posted", posted).apply()
    }

    fun show(key: String) { synchronized(lock) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val state = WatchStore.get(context).state.value
        if (!hasPermission()) return
        val occurrence = ReminderIdentity.parse(key)?.occurrence(state, ZoneId.systemDefault()) ?: return
        val medication = occurrence.medication
        val minute = requireNotNull(occurrence.minute)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .setData(Uri.parse("mona://medication/${medication.id}/$minute"))
            .putExtra("medicationId", medication.id).putExtra("minute", occurrence.scheduledMinute ?: -1),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_medication).setContentTitle(medication.name)
            .setContentText("${medication.doseAt(minute).display()} ${medication.unit} · Time for your medication")
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_medication)
                .setContentTitle("Medication reminder").setContentText("Open Mona to view").build())
            .setContentIntent(open).addAction(R.drawable.ic_medication, "Review & log", open)
            .setAutoCancel(true).setOnlyAlertOnce(true)
            .extend(NotificationCompat.WearableExtender().setDismissalId(key)).build()
        try {
            NotificationManagerCompat.from(context).notify(key, 1, notification)
        } catch (_: SecurityException) {
            return
        }
        val posted = prefs.getStringSet("posted", emptySet()).orEmpty().toMutableSet().apply { add(key) }
        prefs.edit().putStringSet("posted", posted.toList().takeLast(100).toSet()).apply()
    } }
    private fun alarmIntent(key: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData(Uri.parse("mona://reminder/$key")).putExtra("key", key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    companion object {
        const val CHANNEL = "mona_medication"
        private val lock = Any()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = ReminderScheduler(context)
        intent.getStringExtra("key")?.let(scheduler::show)
        scheduler.reschedule()
        WatchSyncWorker.enqueue(context, requestRefresh = true)
    }
}

class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        ReminderScheduler(context).reschedule()
        WatchSyncWorker.enqueue(context, requestRefresh = true)
    }
}
