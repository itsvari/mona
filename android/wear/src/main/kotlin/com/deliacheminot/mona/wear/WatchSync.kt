package com.deliacheminot.mona.wear

import android.app.Application
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class WearApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("mona-periodic-sync", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WatchSyncWorker>(6, TimeUnit.HOURS).setInputData(workDataOf("refresh" to true)).build())
        WatchSyncWorker.enqueue(this, requestRefresh = true)
    }
}

class WatchSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = syncMutex.withLock { withContext(Dispatchers.IO) {
        val store = WatchStore.get(applicationContext)
        val client = Wearable.getDataClient(applicationContext)
        try {
            val localNode = Wearable.getNodeClient(applicationContext).localNode.await().id
            val items = client.dataItems.await()
            var updatesReadable = true
            try {
                for (item in items) {
                    if (item.uri.host != localNode && !receive(applicationContext, item)) updatesReadable = false
                }
            } finally { items.release() }
            for (action in store.state.value.pending) {
                val request = PutDataMapRequest.create(action.path).apply { dataMap.putString("json", action.command) }
                client.putDataItem(request.asPutDataRequest().setUrgent()).await()
            }
            for (path in store.cleanupPaths()) {
                val uri = android.net.Uri.Builder().scheme("wear").authority(localNode).path(path).build()
                client.deleteDataItems(uri).await()
                store.completeCleanup(path)
            }
            if (inputData.getBoolean("refresh", false)) {
                val request = PutDataMapRequest.create("$DATA_ROOT/refresh/${store.installationId}").apply {
                    dataMap.putString("json", "{\"at\":${System.currentTimeMillis()}}")
                }
                client.putDataItem(request.asPutDataRequest().setUrgent()).await()
            }
            if (updatesReadable) store.error(null)
            ReminderScheduler(applicationContext).reschedule()
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            store.error("Waiting for the phone. Your saved actions will sync automatically.")
            Result.retry()
        }
    } }
    companion object {
        private val syncMutex = Mutex()
        fun enqueue(context: Context, requestRefresh: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork("mona-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<WatchSyncWorker>().setInputData(workDataOf("refresh" to requestRefresh)).build())
        }
        internal suspend fun receive(context: Context, item: DataItem): Boolean {
            val path = item.uri.path ?: return true
            val store = WatchStore.get(context)
            if (path != "$DATA_ROOT/snapshot" && !path.startsWith("$DATA_ROOT/results/${store.installationId}/")) return true
            val map = DataMapItem.fromDataItem(item).dataMap
            val json = map.getString("json") ?: map.getAsset("snapshot")?.let { asset ->
                val response = Wearable.getDataClient(context).getFdForAsset(asset).await()
                try {
                    response.inputStream.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 8 * 1024 * 1024) { "Mona's data is too large to sync." }
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                } finally { response.release() }
            } ?: return true
            try {
                if (path == "$DATA_ROOT/snapshot") store.acceptSnapshot(json) else {
                    val id = org.json.JSONObject(json).getString("id")
                    require(path == "$DATA_ROOT/results/${store.installationId}/$id")
                    store.acceptReceipt(json)
                }
                return true
            } catch (_: IllegalArgumentException) {
                store.error("This update could not be read. Update Mona on both devices.")
            } catch (_: org.json.JSONException) {
                store.error("This update could not be read. Update Mona on both devices.")
            } catch (_: java.time.DateTimeException) {
                store.error("This update could not be read. Update Mona on both devices.")
            }
            return false
        }
    }
}

class MonaDataListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        if (events.any { it.type == DataEvent.TYPE_CHANGED &&
                (it.dataItem.uri.path == "$DATA_ROOT/snapshot" || it.dataItem.uri.path?.startsWith("$DATA_ROOT/results/") == true) }) {
            WatchSyncWorker.enqueue(this)
        }
    }
}
