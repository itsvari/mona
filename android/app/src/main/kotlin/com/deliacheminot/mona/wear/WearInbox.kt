package com.deliacheminot.mona.wear

import android.content.Context

internal class WearInbox(context: Context) {
    private val preferences = context.getSharedPreferences("wear_inbox", Context.MODE_PRIVATE)

    fun put(command: WearCommand): Boolean = synchronized(lock) {
        val key = "command:${command.path}"
        if (preferences.getString(key, null) == command.json) return false
        commit(preferences.edit().putString(key, command.json).putBoolean("seenWatch", true)
            .putLong("requested", requested() + 1))
        true
    }

    fun refresh(path: String, value: String): Boolean = synchronized(lock) {
        if (preferences.getString("refresh:$path", null) == value) return false
        commit(preferences.edit().putString("refresh:$path", value).putBoolean("seenWatch", true)
            .putLong("requested", requested() + 1))
        true
    }

    fun deleted(path: String): Boolean = synchronized(lock) {
        val result = WearProtocol.resultPath(path) ?: return false
        commit(preferences.edit().remove("command:$path").putBoolean("delete:$result", true)
            .putLong("requested", requested() + 1))
        true
    }

    fun request() = synchronized(lock) {
        commit(preferences.edit().putLong("requested", requested() + 1))
    }

    fun commands(): List<WearCommand> = synchronized(lock) {
        preferences.all.mapNotNull { (key, value) ->
            if (key.startsWith("command:") && value is String)
                WearCommand(key.removePrefix("command:"), value) else null
        }.sortedBy { it.path }
    }

    fun deletions(): List<String> = synchronized(lock) {
        preferences.all.keys.filter { it.startsWith("delete:") }.map { it.removePrefix("delete:") }
    }

    fun deletedResult(path: String) = synchronized(lock) {
        commit(preferences.edit().remove("delete:$path"))
    }

    fun complete(version: Long, commands: List<WearCommand>, processed: Set<String>) = synchronized(lock) {
        val edit = preferences.edit().putLong("completed", version)
        commands.filter { it.path in processed }.forEach { command ->
            if (preferences.getString("command:${command.path}", null) == command.json) {
                edit.remove("command:${command.path}")
            }
        }
        commit(edit)
    }

    fun requested(): Long = preferences.getLong("requested", 0)
    fun completed(): Long = preferences.getLong("completed", -1)
    fun localCompleted(): Long = preferences.getLong("localCompleted", -1)
    fun completeLocal(version: Long) = synchronized(lock) {
        commit(preferences.edit().putLong("localCompleted", maxOf(version, localCompleted())))
    }
    fun hasSeenWatch(): Boolean = preferences.getBoolean("seenWatch", false)

    private fun commit(editor: android.content.SharedPreferences.Editor) {
        check(editor.commit()) { "Unable to persist Wear synchronization" }
    }

    companion object {
        private val lock = Any()
    }
}
