package com.deliacheminot.mona.wear

import org.json.JSONObject

internal data class WearCommand(val path: String, val json: String)

internal data class WearBatch(
    val snapshot: String,
    val results: List<WearCommand>,
    val processedPaths: Set<String>,
)

internal object WearProtocol {
    const val CHANNEL = "mona/wear"
    const val SNAPSHOT = "/mona/v1/snapshot"
    private const val UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private val uuid = Regex("^$UUID$")
    private val commandPath = Regex("^/mona/v1/commands/($UUID)/($UUID)$")
    private val refreshPath = Regex("^/mona/v1/refresh/($UUID)$")

    fun command(path: String, json: String): WearCommand? = runCatching {
        val pathIds = commandPath.matchEntire(path) ?: return null
        if (json.toByteArray(Charsets.UTF_8).size > 16 * 1024) return null
        val value = JSONObject(json)
        if (value.optInt("version") != 1 ||
            value.optString("installationId") != pathIds.groupValues[1] ||
            value.optString("id") != pathIds.groupValues[2] ||
            !uuid.matches(value.optString("datasetId"))
        ) return null
        WearCommand(path, json)
    }.getOrNull()

    fun isRefresh(path: String): Boolean = refreshPath.matches(path)

    fun resultPath(path: String): String? =
        if (commandPath.matches(path)) path.replace("/commands/", "/results/") else null

    fun commandPathForResult(path: String): String? {
        if (!path.startsWith("/mona/v1/results/")) return null
        val command = path.replace("/results/", "/commands/")
        return if (commandPath.matches(command)) command else null
    }

    fun batch(arguments: Any?, commands: List<WearCommand>): WearBatch {
        val values = arguments as? Map<*, *> ?: error("Invalid completion")
        val snapshot = values["snapshot"] as? String ?: error("Missing snapshot")
        require(snapshot.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
        require(JSONObject(snapshot).optInt("version") == 1)
        val sourcePaths = commands.map { it.path }.toSet()
        val expectedResults = sourcePaths.mapNotNull(::resultPath).toSet()
        val results = (values["results"] as? List<*>)?.map { entry ->
            val result = entry as? Map<*, *> ?: error("Invalid result")
            val path = result["path"] as? String ?: error("Missing result path")
            val json = result["json"] as? String ?: error("Missing result body")
            require(path in expectedResults)
            require(json.toByteArray(Charsets.UTF_8).size <= 16 * 1024)
            val body = JSONObject(json)
            require(body.optInt("version") == 1)
            require(path.substringAfterLast('/') == body.optString("id"))
            WearCommand(path, json)
        } ?: error("Missing results")
        val processed = (values["processedPaths"] as? List<*>)?.map { entry ->
            val path = entry as? String ?: error("Invalid processed path")
            require(path in sourcePaths)
            require(results.any { it.path == resultPath(path) })
            path
        }?.toSet() ?: error("Missing processed paths")
        return WearBatch(snapshot, results, processed)
    }
}
