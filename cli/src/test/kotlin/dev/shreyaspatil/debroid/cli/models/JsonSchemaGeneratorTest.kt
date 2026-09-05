package dev.shreyaspatil.debroid.cli.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonSchemaGeneratorTest {

    @Serializable
    private enum class TestEnum {
        FIRST, SECOND
    }

    @Serializable
    private data class PrimitiveModel(
        val strVal: String,
        val intVal: Int,
        val longVal: Long,
        val doubleVal: Double,
        val boolVal: Boolean,
        val enumVal: TestEnum
    )

    @Serializable
    private data class NullableModel(
        val optionalStr: String?,
        val optionalInt: Int?
    )

    @Serializable
    private data class CollectionModel(
        val tags: List<String>,
        val scores: Map<String, Int>
    )

    @Serializable
    private data class NestedInner(
        val innerName: String
    )

    @Serializable
    private data class NestedOuter(
        val id: String,
        val inner: NestedInner
    )

    @Serializable
    private data class RecursiveNode(
        val name: String,
        val child: RecursiveNode? = null
    )

    @Test
    fun `test primitives schema generation`() {
        val schema = JsonSchemaGenerator.generate(PrimitiveModel.serializer().descriptor).jsonObject

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        val props = schema["properties"]?.jsonObject
        assertNotNull(props)

        assertEquals("string", props?.get("strVal")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("integer", props?.get("intVal")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("integer", props?.get("longVal")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("number", props?.get("doubleVal")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("boolean", props?.get("boolVal")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("string", props?.get("enumVal")?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `test nullable properties schema generation`() {
        val schema = JsonSchemaGenerator.generate(NullableModel.serializer().descriptor).jsonObject
        val props = schema["properties"]?.jsonObject
        assertNotNull(props)

        val strProp = props?.get("optionalStr")?.jsonObject
        assertEquals("string", strProp?.get("type")?.jsonPrimitive?.content)
        assertEquals("true", strProp?.get("nullable")?.jsonPrimitive?.content)

        val intProp = props?.get("optionalInt")?.jsonObject
        assertEquals("integer", intProp?.get("type")?.jsonPrimitive?.content)
        assertEquals("true", intProp?.get("nullable")?.jsonPrimitive?.content)
    }

    @Test
    fun `test collection and map properties schema generation`() {
        val schema = JsonSchemaGenerator.generate(CollectionModel.serializer().descriptor).jsonObject
        val props = schema["properties"]?.jsonObject
        assertNotNull(props)

        val tagsProp = props?.get("tags")?.jsonObject
        assertEquals("array", tagsProp?.get("type")?.jsonPrimitive?.content)
        assertEquals("string", tagsProp?.get("items")?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val scoresProp = props?.get("scores")?.jsonObject
        assertEquals("object", scoresProp?.get("type")?.jsonPrimitive?.content)
        val addProps = scoresProp?.get("additionalProperties")?.jsonObject
        assertEquals("integer", addProps?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `test nested objects schema generation`() {
        val schema = JsonSchemaGenerator.generate(NestedOuter.serializer().descriptor).jsonObject
        val props = schema["properties"]?.jsonObject
        assertNotNull(props)

        val innerProp = props?.get("inner")?.jsonObject
        assertEquals("object", innerProp?.get("type")?.jsonPrimitive?.content)
        val innerProps = innerProp?.get("properties")?.jsonObject
        assertEquals("string", innerProps?.get("innerName")?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `test recursive self-referential model schema generation avoids infinite loop`() {
        val schema = JsonSchemaGenerator.generate(RecursiveNode.serializer().descriptor).jsonObject
        val props = schema["properties"]?.jsonObject
        assertNotNull(props)

        val childProp = props?.get("child")?.jsonObject
        assertNotNull(childProp)
        assertEquals("object", childProp?.get("type")?.jsonPrimitive?.content)

        val childProps = childProp?.get("properties")?.jsonObject
        val innerChild = childProps?.get("child")?.jsonObject
        assertNotNull(innerChild)
        assertTrue(innerChild?.get("\$ref")?.jsonPrimitive?.content?.contains("RecursiveNode") == true)
    }

    @Test
    fun `test ListSerializer schema generation`() {
        val listSerializer = ListSerializer(CliVariableInfo.serializer())
        val schema = JsonSchemaGenerator.generate(listSerializer.descriptor).jsonObject

        assertEquals("array", schema["type"]?.jsonPrimitive?.content)
        val items = schema["items"]?.jsonObject
        assertEquals("object", items?.get("type")?.jsonPrimitive?.content)
        val props = items?.get("properties")?.jsonObject
        assertNotNull(props?.get("name"))
        assertNotNull(props?.get("valuePreview"))
    }

    @Test
    fun `test MapSerializer schema generation`() {
        val mapSerializer = MapSerializer(String.serializer(), CliVariableInfo.serializer())
        val schema = JsonSchemaGenerator.generate(mapSerializer.descriptor).jsonObject

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        val addProps = schema["additionalProperties"]?.jsonObject
        assertEquals("object", addProps?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `test real CliPauseStateResult schema generation`() {
        val schema = JsonSchemaGenerator.generate(CliPauseStateResult.serializer().descriptor).jsonObject
        val props = schema["properties"]?.jsonObject
        assertNotNull(props)

        assertEquals("string", props?.get("threadId")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("array", props?.get("frames")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("array", props?.get("locals")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("array", props?.get("instanceVariables")?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `test real CliObjectInspectionResult schema generation with recursive nested property`() {
        val schema = JsonSchemaGenerator.generate(CliObjectInspectionResult.serializer().descriptor).jsonObject
        val props = schema["properties"]?.jsonObject
        assertNotNull(props)

        assertEquals("string", props?.get("objectId")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("object", props?.get("fields")?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val nestedProp = props?.get("nested")?.jsonObject
        assertEquals("object", nestedProp?.get("type")?.jsonPrimitive?.content)
        assertEquals("true", nestedProp?.get("nullable")?.jsonPrimitive?.content)

        val addProps = nestedProp?.get("additionalProperties")?.jsonObject
        assertEquals("object", addProps?.get("type")?.jsonPrimitive?.content)
        assertTrue(addProps?.get("\$ref")?.jsonPrimitive?.content?.contains("CliObjectInspectionResult") == true)
    }

    @Test
    fun `test real CliEventPollResult schema generation`() {
        val schema = JsonSchemaGenerator.generate(CliEventPollResult.serializer().descriptor).jsonObject
        val props = schema["properties"]?.jsonObject
        assertNotNull(props)

        val eventsProp = props?.get("events")?.jsonObject
        assertEquals("array", eventsProp?.get("type")?.jsonPrimitive?.content)

        val eventItem = eventsProp?.get("items")?.jsonObject?.get("properties")?.jsonObject
        assertNotNull(eventItem?.get("eventType"))
        assertNotNull(eventItem?.get("stacktrace"))

        assertEquals("string", props?.get("nextCursor")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("boolean", props?.get("hasMore")?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val droppedProp = props?.get("droppedEventsSinceLastPoll")?.jsonObject
        assertEquals("integer", droppedProp?.get("type")?.jsonPrimitive?.content)
        assertEquals("true", droppedProp?.get("nullable")?.jsonPrimitive?.content)
    }

    @Test
    fun `test CliEventPollResult serializes droppedEventsSinceLastPoll only when non-null`() {
        val json = Json { explicitNulls = false }
        val withoutDropped = CliEventPollResult(
            events = emptyList(),
            nextCursor = "1",
            hasMore = false,
            droppedEventsSinceLastPoll = null
        )
        val serializedWithout = json.encodeToString(CliEventPollResult.serializer(), withoutDropped)
        assertFalse(serializedWithout.contains("droppedEventsSinceLastPoll"))

        val withDropped = CliEventPollResult(
            events = emptyList(),
            nextCursor = "10",
            hasMore = false,
            droppedEventsSinceLastPoll = 500L
        )
        val serializedWith = json.encodeToString(CliEventPollResult.serializer(), withDropped)
        assertTrue(serializedWith.contains("\"droppedEventsSinceLastPoll\":500"))
    }
}
