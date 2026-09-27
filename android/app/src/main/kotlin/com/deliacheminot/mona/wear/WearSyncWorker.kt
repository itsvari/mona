package com.deliacheminot.mona.wear

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WearSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val inbox = WearInbox(applicationContext)
        if (inbox.requested() <= inbox.completed() && inbox.commands().isEmpty() && inbox.deletions().isEmpty()) {
            return Result.success()
        }
        try {
            return withTimeout(150_000) {
                val transport = availableTransport(inbox)
                val commands = inbox.commands()
                val version = inbox.requested()
                val needsTransport = commands.isNotEmpty() || inbox.hasSeenWatch() ||
                    inbox.deletions().isNotEmpty() || transport?.hasWatch == true
                if (!needsTransport) {
                    inbox.complete(version, emptyList(), emptySet())
                    return@withTimeout Result.success()
                }
                val batch = PhoneWearRuntime.execute(applicationContext, commands)
                inbox.completeLocal(version)
                // Dart committed before returning. UI must refresh even if radio publishing needs retry.
                ForegroundWearBridge.refresh()
                if (transport == null) return@withTimeout Result.retry()
                for (result in batch.results) publish(transport.data, result.path, result.json)
                publish(transport.data, WearProtocol.SNAPSHOT, batch.snapshot)
                for (path in inbox.deletions()) {
                    val uri = Uri.Builder().scheme("wear").authority(transport.localNode).path(path).build()
                    transport.data.deleteDataItems(uri, DataClient.FILTER_LITERAL).awaitTask()
                    inbox.deletedResult(path)
                }
                inbox.complete(version, commands, batch.processedPaths)
                Result.success()
            }
        } catch (_: TimeoutCancellationException) {
            return Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.retry()
        }
    }

    private data class Transport(val data: DataClient, val localNode: String, val hasWatch: Boolean)

    private suspend fun availableTransport(inbox: WearInbox): Transport? {
        if (GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(applicationContext) != ConnectionResult.SUCCESS) {
            return null
        }
        return try {
            withTimeout(5_000) {
                val data = Wearable.getDataClient(applicationContext)
                val localNode = Wearable.getNodeClient(applicationContext).localNode.awaitTask().id
                scan(data, inbox, localNode)
                Transport(data, localNode, inbox.hasSeenWatch() || hasWatch())
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun scan(data: DataClient, inbox: WearInbox, localNode: String) {
        val items = data.dataItems.awaitTask()
        try {
            val commands = mutableSetOf<String>()
            val receipts = mutableListOf<String>()
            for (item in items) {
                val path = item.uri.path ?: continue
                if (item.uri.host == localNode) {
                    WearProtocol.commandPathForResult(path)?.let(receipts::add)
                    continue
                }
                if (WearProtocol.resultPath(path) != null) commands.add(path)
                val json = runCatching { DataMapItem.fromDataItem(item).dataMap.getString("json") }.getOrNull() ?: continue
                WearProtocol.command(path, json)?.let(inbox::put)
                if (WearProtocol.isRefresh(path) && json.length <= 1024) inbox.refresh(path, json)
            }
            receipts.filter { it !in commands }.forEach(inbox::deleted)
        } finally {
            items.release()
        }
    }

    private suspend fun hasWatch(): Boolean =
        Wearable.getCapabilityClient(applicationContext)
            .getCapability("mona_wear", CapabilityClient.FILTER_ALL).awaitTask().nodes.isNotEmpty()

    private suspend fun publish(data: DataClient, path: String, json: String) {
        val request = PutDataMapRequest.create(path)
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (path == WearProtocol.SNAPSHOT && bytes.size > 80 * 1024) {
            request.dataMap.putAsset("snapshot", Asset.createFromBytes(bytes))
        } else {
            request.dataMap.putString("json", json)
        }
        data.putDataItem(request.asPutDataRequest().setUrgent()).awaitTask()
    }

    companion object {
        private const val WORK_NAME = "mona-wear-sync"

        fun request(context: Context) {
            WearInbox(context).request()
            val local = OneTimeWorkRequestBuilder<WearLocalRefreshWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "mona-local-reminders", ExistingWorkPolicy.APPEND_OR_REPLACE, local,
            )
            enqueue(context)
        }

        internal fun enqueue(context: Context) {
            val work = OneTimeWorkRequestBuilder<WearSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}

class WearLocalRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val inbox = WearInbox(applicationContext)
        val version = inbox.requested()
        if (version <= inbox.localCompleted()) return Result.success()
        return try {
            withTimeout(120_000) {
                PhoneWearRuntime.execute(applicationContext, emptyList())
                inbox.completeLocal(version)
                Result.success()
            }
        } catch (_: TimeoutCancellationException) {
            Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resume(task.result)
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Wear service unavailable"))
    }
}
