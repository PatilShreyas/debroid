package dev.shreyaspatil.debroid.cli.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class JsonSchemaGoldenTest {

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val goldenDir = File("src/test/resources/golden-schemas")
    private val isUpdateGolden = System.getProperty("updateGoldenSchemas") == "true"

    @Test
    fun `verify golden schema for CliSessionStatus`() {
        assertGoldenSchema("session-status.schema.json", CliSessionStatus.serializer())
    }

    @Test
    fun `verify golden schema for CliBreakpointInfo`() {
        assertGoldenSchema("breakpoint-info.schema.json", CliBreakpointInfo.serializer())
    }

    @Test
    fun `verify golden schema for CliPauseStateResult`() {
        assertGoldenSchema("pause-state-result.schema.json", CliPauseStateResult.serializer())
    }

    @Test
    fun `verify golden schema for CliObjectInspectionResult`() {
        assertGoldenSchema("object-inspection-result.schema.json", CliObjectInspectionResult.serializer())
    }

    @Test
    fun `verify golden schema for CliEventPollResult`() {
        assertGoldenSchema("event-poll-result.schema.json", CliEventPollResult.serializer())
    }

    @Test
    fun `verify golden schema for CliVariableInfo list`() {
        assertGoldenSchema("variable-info-list.schema.json", ListSerializer(CliVariableInfo.serializer()))
    }

    @Test
    fun `verify golden schema for CliStackFrameInfo list`() {
        assertGoldenSchema("stack-frame-info-list.schema.json", ListSerializer(CliStackFrameInfo.serializer()))
    }

    @Test
    fun `verify golden schema for CliExceptionBreakpointResult`() {
        assertGoldenSchema("exception-breakpoint-result.schema.json", CliExceptionBreakpointResult.serializer())
    }

    @Test
    fun `verify golden schema for CliWatchpointResult`() {
        assertGoldenSchema("watchpoint-result.schema.json", CliWatchpointResult.serializer())
    }

    @Test
    fun `verify golden schema for CliDetachedResult`() {
        assertGoldenSchema("detached-result.schema.json", CliDetachedResult.serializer())
    }

    @Test
    fun `verify golden schema for CliShutdownResult`() {
        assertGoldenSchema("shutdown-result.schema.json", CliShutdownResult.serializer())
    }

    @Test
    fun `verify golden schema for CliStatusResult`() {
        assertGoldenSchema("status-result.schema.json", CliStatusResult.serializer())
    }

    @Test
    fun `verify golden schema for CliUpdateResult`() {
        assertGoldenSchema("update-result.schema.json", CliUpdateResult.serializer())
    }

    @Test
    fun `verify golden schema for CliPointsResult`() {
        assertGoldenSchema("points-result.schema.json", CliPointsResult.serializer())
    }

    @Test
    fun `verify golden schema for CliVersionResult`() {
        assertGoldenSchema("version-result.schema.json", CliVersionResult.serializer())
    }

    @Test
    fun `verify golden schema for CliThreadInfo list`() {
        assertGoldenSchema("thread-info-list.schema.json", ListSerializer(CliThreadInfo.serializer()))
    }

    private fun assertGoldenSchema(goldenFileName: String, serializer: KSerializer<*>) {
        val generatedElement = JsonSchemaGenerator.generate(serializer.descriptor)
        val generatedJsonStr = prettyJson.encodeToString(JsonElement.serializer(), generatedElement)

        goldenDir.mkdirs()
        val goldenFile = File(goldenDir, goldenFileName)

        if (isUpdateGolden || !goldenFile.exists()) {
            goldenFile.writeText(generatedJsonStr)
            println("Updated golden schema file: ${goldenFile.absolutePath}")
            return
        }

        val expectedJsonStr = goldenFile.readText()
        assertEquals(
            expectedJsonStr.trim(),
            generatedJsonStr.trim(),
            "Schema mismatch for $goldenFileName! If this change is intentional, " +
                "run with -DupdateGoldenSchemas=true to update."
        )
    }
}
