package com.deliacheminot.mona.wear

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class WatchStoreTest {
    @Test fun savesSnapshotAndPendingActionsAcrossProcessRecreation() {
        val context = MemoryContext()
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        persistCommand(context)
        val recreated = store(context)
        assertEquals(store.installationId, recreated.installationId)
        assertEquals(TEST_DATASET, recreated.state.value.snapshot!!.datasetId)
        assertEquals(TEST_OPERATION, recreated.state.value.pending.single().id)
        assertEquals(1, recreated.state.value.history().size)
        assertTrue(recreated.state.value.history().single().pending)
    }

    @Test fun receiptBeforeSnapshotKeepsDoseUntilConfirmationArrives() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        store.acceptReceipt(receipt())
        assertTrue(store.state.value.actions.single().awaitingSnapshot)
        assertEquals(1, store.state.value.history().size)
        assertTrue(store.state.value.history().single().pending)
        store.acceptSnapshot(snapshot(history = listOf(intake()), revision = 2).json())
        assertEquals(1, store.state.value.history().size)
        assertEquals("100", store.state.value.history().single().id)
        assertFalse(store.state.value.history().single().pending)
    }

    @Test fun snapshotBeforeReceiptNeverDisplaysTheSameDoseTwice() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot(history = listOf(intake()), revision = 2).json())
        assertEquals("The snapshot already contains this scheduled dose", listOf("100"), store.state.value.history().map { it.id })
        store.acceptReceipt(receipt())
        assertFalse(store.state.value.actions.single().awaitingSnapshot)
        assertEquals(listOf("100"), store.state.value.history().map { it.id })
    }

    @Test fun unrelatedOlderSnapshotCannotClearAnAcknowledgedDose() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot(revision = 0).json())
        store.acceptReceipt(receipt())
        store.acceptSnapshot(snapshot(revision = 1).json())
        assertTrue(store.state.value.actions.single().awaitingSnapshot)
        assertEquals(TEST_OPERATION, store.state.value.history().single().id)
    }

    @Test fun cannotDismissPendingOrAppliedDoseBeforeSnapshot() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        store.dismissResult(TEST_OPERATION)
        assertEquals(1, store.state.value.pending.size)
        store.acceptReceipt(receipt())
        store.dismissResult(TEST_OPERATION)
        assertEquals(1, store.state.value.actions.size)
        store.acceptSnapshot(snapshot(history = listOf(intake()), revision = 2).json())
        store.dismissResult(TEST_OPERATION)
        assertTrue(store.state.value.actions.isEmpty())
        assertEquals(1, store.state.value.history().size)
    }

    @Test fun replacementDatasetRejectsOldPendingActionsWithoutReplayingThem() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        val replacement = "78662994-ae79-46dc-8973-d932e89921cf"
        store.acceptSnapshot(snapshot(dataset = replacement, revision = 0).json())
        assertEquals(replacement, store.state.value.snapshot!!.datasetId)
        assertEquals("rejected", store.state.value.actions.single().status)
        assertTrue(store.state.value.pending.isEmpty())
        assertTrue(store.state.value.history().isEmpty())
        assertEquals("rejected", store(context).state.value.actions.single().status)
    }

    @Test fun staleAndDuplicateSnapshotsCannotRestoreDeletedSchedules() {
        val context = MemoryContext()
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        store.acceptSnapshot(snapshot(medications = emptyList(), revision = 2).json())
        store.acceptSnapshot(snapshot(revision = 1).json())
        store.acceptSnapshot(snapshot(revision = 2).json())
        assertTrue(store.state.value.snapshot!!.schedules.isEmpty())
    }

    @Test fun foreignReceiptDoesNotAcknowledgeOrHidePendingAction() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        store.acceptReceipt(receipt(dataset = "another-dataset"))
        assertEquals("pending", store.state.value.actions.single().status)
        assertEquals(1, store.state.value.history().size)
    }

    @Test fun rejectedDoseStopsSuppressingRemindersAndCanBeDismissed() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        assertEquals(1, store.state.value.history().size)
        store.acceptReceipt(receipt(status = "rejected"))
        assertTrue(store.state.value.history().isEmpty())
        store.dismissResult(TEST_OPERATION)
        assertTrue(store.state.value.actions.isEmpty())
        assertTrue(store(context).state.value.actions.isEmpty())
    }

    @Test fun dismissalKeepsTransportCleanupUntilDeletionSucceeds() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        store.acceptReceipt(receipt(status = "rejected"))
        val path = store.state.value.actions.single().path
        store.dismissResult(TEST_OPERATION)
        assertTrue(store.state.value.actions.isEmpty())
        assertEquals(setOf(path), store.cleanupPaths())
        val afterRestart = store(context)
        assertEquals(setOf(path), afterRestart.cleanupPaths())
        afterRestart.completeCleanup(path)
        assertTrue(store(context).cleanupPaths().isEmpty())
    }

    @Test fun deletingRemoteCommandDoesNotDismissVisibleResult() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        store.acceptReceipt(receipt(status = "rejected"))
        val path = store.state.value.actions.single().path
        assertEquals(setOf(path), store.cleanupPaths())
        store.completeCleanup(path)
        assertTrue(store.cleanupPaths().isEmpty())
        assertEquals("rejected", store.state.value.actions.single().status)
        assertEquals("rejected", store(context).state.value.actions.single().status)
    }

    @Test fun retiredDatasetCannotReplaceCurrentDataOrReviveOldAction() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        val replacement = "78662994-ae79-46dc-8973-d932e89921cf"
        store.acceptSnapshot(snapshot(dataset = replacement, revision = 1).json())
        val afterRestart = store(context)
        afterRestart.acceptSnapshot(snapshot(revision = 999).json())
        afterRestart.acceptReceipt(receipt())
        assertEquals(replacement, afterRestart.state.value.snapshot!!.datasetId)
        assertEquals("rejected", afterRestart.state.value.actions.single().status)
        assertEquals(1, afterRestart.cleanupPaths().size)
        assertTrue(afterRestart.state.value.history().isEmpty())
    }

    @Test fun pendingOldRevisionDoesNotSuppressTheChangedMedicationSchedule() {
        val context = MemoryContext()
        persistCommand(context)
        val store = store(context)
        store.acceptSnapshot(snapshot().json())
        assertEquals(1, store.state.value.history().size)
        store.acceptSnapshot(snapshot(listOf(medication().copy(revision = "new-dose")), revision = 2).json())
        assertTrue(store.state.value.history().isEmpty())
        assertEquals(1, store.state.value.pending.size)
    }

    private fun persistCommand(context: MemoryContext) {
        context.getSharedPreferences("mona_wear", 0).edit().putString("command.$TEST_OPERATION", command()).commit()
    }

    private fun store(context: MemoryContext): WatchStore = WatchStore::class.java
        .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
}

private class MemoryContext : ContextWrapper(null) {
    private val files = mutableMapOf<String, SharedPreferences>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        files.getOrPut(name.orEmpty()) { MemoryPreferences() }
}

private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { changes[requireNotNull(key)] = value }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value
            return true
        }
        override fun apply() { commit() }
    }
}
