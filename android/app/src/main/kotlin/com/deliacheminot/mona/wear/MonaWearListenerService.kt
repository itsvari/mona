package com.deliacheminot.mona.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class MonaWearListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val inbox = WearInbox(applicationContext)
        var changed = false
        for (event in events) {
            val path = event.dataItem.uri.path ?: continue
            if (event.type == DataEvent.TYPE_DELETED) {
                changed = inbox.deleted(path) || changed
                continue
            }
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val json = runCatching { DataMapItem.fromDataItem(event.dataItem).dataMap.getString("json") }
                .getOrNull() ?: continue
            val command = WearProtocol.command(path, json)
            changed = when {
                command != null -> inbox.put(command) || changed
                WearProtocol.isRefresh(path) && json.length <= 1024 -> inbox.refresh(path, json) || changed
                else -> changed
            }
        }
        if (changed) WearSyncWorker.enqueue(applicationContext)
    }
}
