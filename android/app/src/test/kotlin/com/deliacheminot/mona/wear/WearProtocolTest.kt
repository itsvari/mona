package com.deliacheminot.mona.wear

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WearProtocolTest {
    private val installation = "5eb07565-3ca1-48e2-a73d-7e0ad1ebd4aa"
    private val id = "9a461de3-9060-4793-a5c8-6a0b8429d3c6"
    private val path = "/mona/v1/commands/$installation/$id"
    private val json = JSONObject().put("version", 1).put("installationId", installation)
        .put("id", id).put("datasetId", installation).put("kind", "recordDose").put("payload", JSONObject()).toString()

    @Test
    fun acceptsMatchingEnvelopeAndPath() {
        assertNotNull(WearProtocol.command(path, json))
        assertEquals("/mona/v1/results/$installation/$id", WearProtocol.resultPath(path))
    }

    @Test
    fun rejectsSpoofedIdentityAndUnexpectedPaths() {
        assertNull(WearProtocol.command(path.replace(id, installation), json))
        assertNull(WearProtocol.command("$path/extra", json))
        assertNull(WearProtocol.command(path.replace("/commands/", "/results/"), json))
        assertNull(WearProtocol.resultPath("/mona/v1/snapshot"))
    }

    @Test
    fun rejectsOversizedMalformedAndUnsupportedCommands() {
        assertNull(WearProtocol.command(path, JSONObject(json).put("notes", "x".repeat(16 * 1024)).toString()))
        assertNull(WearProtocol.command(path, "not json"))
        assertNull(WearProtocol.command(path, JSONObject(json).put("version", 2).toString()))
        assertNull(WearProtocol.command(path, JSONObject(json).put("datasetId", "invalid").toString()))
    }

    @Test
    fun completionRequiresReceiptForEveryRemovedCommand() {
        val input = listOf(WearCommand(path, json))
        val arguments = mapOf("snapshot" to "{\"version\":1}", "results" to emptyList<Any>(), "processedPaths" to listOf(path))
        assertThrows(IllegalArgumentException::class.java) { WearProtocol.batch(arguments, input) }
    }

    @Test
    fun completionCannotPublishResultForAnotherOperation() {
        val input = listOf(WearCommand(path, json))
        val arguments = mapOf(
            "snapshot" to "{\"version\":1}",
            "results" to listOf(mapOf("path" to "/mona/v1/results/$installation/$installation", "json" to "{\"version\":1,\"id\":\"$id\"}")),
            "processedPaths" to listOf(path),
        )
        assertThrows(IllegalArgumentException::class.java) { WearProtocol.batch(arguments, input) }
    }

    @Test
    fun completionPreservesCanonicalSnapshotAndCommandIdentity() {
        val input = listOf(WearCommand(path, json))
        val snapshot = "{\"version\":1,\"revision\":7}"
        val arguments = mapOf(
            "snapshot" to snapshot,
            "results" to listOf(mapOf("path" to WearProtocol.resultPath(path), "json" to "{\"version\":1,\"id\":\"$id\"}")),
            "processedPaths" to listOf(path),
        )
        val batch = WearProtocol.batch(arguments, input)
        assertEquals(snapshot, batch.snapshot)
        assertEquals(setOf(path), batch.processedPaths)
    }
}
