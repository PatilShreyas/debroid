package dev.shreyaspatil.debroid.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdditiveClassMergerTest {

    @Test
    fun `mergePreservingRemovedMembers retains removed fields and stubs removed methods in valid bytecode`() {
        val prevClassBytes = buildSyntheticClassFile(
            internalName = "com/example/SampleKt",
            fields = listOf(
                AdditiveClassMerger.MemberSignature(0x0019, "lambda\$-100", "Ljava/lang/Object;"),
                AdditiveClassMerger.MemberSignature(0x0019, "KEPT_FIELD", "I")
            ),
            methods = listOf(
                AdditiveClassMerger.MemberSignature(0x0019, "keptMethod", "()I"),
                AdditiveClassMerger.MemberSignature(0x0019, "removedVoid", "(I)V"),
                AdditiveClassMerger.MemberSignature(0x0019, "removedObj", "(Ljava/lang/String;)Ljava/lang/Object;"),
                AdditiveClassMerger.MemberSignature(0x0019, "removedLong", "(J)J"),
                AdditiveClassMerger.MemberSignature(
                    0x101A,
                    "lambda_100\$lambda\$0",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;"
                )
            )
        )
        val currClassBytes = buildSyntheticClassFile(
            internalName = "com/example/SampleKt",
            fields = listOf(
                AdditiveClassMerger.MemberSignature(0x0019, "KEPT_FIELD", "I"),
                AdditiveClassMerger.MemberSignature(0x0019, "NEW_FIELD", "Ljava/lang/String;")
            ),
            methods = listOf(
                AdditiveClassMerger.MemberSignature(0x0019, "keptMethod", "()I"),
                AdditiveClassMerger.MemberSignature(0x0019, "newMethod", "()I")
            )
        )

        val mergedBytes = AdditiveClassMerger.mergePreservingRemovedMembers(prevClassBytes, currClassBytes)
        val loadedClass = ByteArrayClassLoader().define("com.example.SampleKt", mergedBytes)

        assertNotNull(loadedClass.getDeclaredField("KEPT_FIELD"))
        assertNotNull(loadedClass.getDeclaredField("NEW_FIELD"))
        assertNotNull(loadedClass.getDeclaredField("lambda\$-100"))

        assertEquals(0, loadedClass.getDeclaredMethod("keptMethod").invoke(null))
        assertEquals(0, loadedClass.getDeclaredMethod("newMethod").invoke(null))
        assertNull(loadedClass.getDeclaredMethod("removedVoid", Int::class.javaPrimitiveType).invoke(null, 42))
        assertNull(loadedClass.getDeclaredMethod("removedObj", String::class.java).invoke(null, "arg"))
        assertEquals(0L, loadedClass.getDeclaredMethod("removedLong", Long::class.javaPrimitiveType).invoke(null, 7L))

        val singletonLambda = loadedClass.getDeclaredMethod(
            "lambda_100\$lambda\$0",
            Any::class.java,
            Int::class.javaPrimitiveType
        )
        // ACC_PRIVATE (0x0002) and ACC_SYNTHETIC (0x1000) should be stripped so it matches d8's 0x0018 flags
        assertEquals(0x0018, singletonLambda.modifiers and 0x1FFF)
    }

    @Test
    fun `parseDexMembers and mergeWithPreviousDex preserve d8 synthetic bridges and relaxed lambda flags`() {
        val dexBytes = buildMinimalDexWithMethod(
            classDescriptor = "Lcom/example/SampleKt;",
            methodName = "\$r8\$lambda\$abc123",
            returnType = "V",
            accessFlags = 0x1009
        )
        val parsedDex = AdditiveClassMerger.parseDexMembers(dexBytes)
        assertNotNull(parsedDex)
        assertEquals(1, parsedDex?.methods?.size)
        assertEquals("\$r8\$lambda\$abc123", parsedDex?.methods?.first()?.name)
        assertEquals("()V", parsedDex?.methods?.first()?.descriptor)
        assertEquals(0x1009, parsedDex?.methods?.first()?.accessFlags)

        val currDexBytes = buildMinimalDexWithMethod(
            classDescriptor = "Lcom/example/SampleKt;",
            methodName = "activeMethod",
            returnType = "V",
            accessFlags = 0x0019
        )
        val currClassBytes = buildSyntheticClassFile(
            internalName = "com/example/SampleKt",
            fields = emptyList(),
            methods = listOf(AdditiveClassMerger.MemberSignature(0x0019, "activeMethod", "()V"))
        )

        val reconciledBytes = AdditiveClassMerger.mergeWithPreviousDex(
            previousDexBytes = dexBytes,
            currentDexBytes = currDexBytes,
            currentClassBytes = currClassBytes
        )
        val loadedClass = ByteArrayClassLoader().define("com.example.SampleKt", reconciledBytes)
        val bridgeMethod = loadedClass.getDeclaredMethod("\$r8\$lambda\$abc123")
        assertTrue((bridgeMethod.modifiers and 0x1009) == 0x1009)
    }

    @Test
    fun `aligns method ACC_PRIVATE flag with baseline DEX when Kotlin compiler visibility changes`() {
        val prevDex = AdditiveClassMerger.DexClassMembers(
            fields = emptyList(),
            methods = listOf(
                AdditiveClassMerger.MemberSignature(0x0018, "helperLambda", "()I"),
                AdditiveClassMerger.MemberSignature(0x1009, "\$jacocoInit", "()I")
            )
        )
        val currDex = AdditiveClassMerger.DexClassMembers(
            fields = emptyList(),
            methods = listOf(
                AdditiveClassMerger.MemberSignature(0x001A, "helperLambda", "()I")
            )
        )
        val currClassBytes = buildSyntheticClassFile(
            internalName = "com/example/ForYouScreenKt",
            fields = emptyList(),
            methods = listOf(AdditiveClassMerger.MemberSignature(0x001A, "helperLambda", "()I"))
        )

        val mergedBytes = AdditiveClassMerger.mergeWithPreviousDexMembers(
            prevDex = prevDex,
            currDex = currDex,
            currentClassBytes = currClassBytes
        )
        val loadedClass = ByteArrayClassLoader().define("com.example.ForYouScreenKt", mergedBytes)
        val helperMethod = loadedClass.getDeclaredMethod("helperLambda")
        assertEquals(0, helperMethod.modifiers and 0x0002)
        assertNotNull(loadedClass.getDeclaredMethod("\$jacocoInit"))
    }

    private class ByteArrayClassLoader : ClassLoader(AdditiveClassMergerTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    private fun buildSyntheticClassFile(
        internalName: String,
        fields: List<AdditiveClassMerger.MemberSignature>,
        methods: List<AdditiveClassMerger.MemberSignature>
    ): ByteArray {
        val utf8List = mutableListOf<String>()
        fun addUtf8(str: String): Int {
            val existing = utf8List.indexOf(str)
            if (existing >= 0) return existing + 1
            utf8List.add(str)
            return utf8List.size
        }

        val thisNameUtf8 = addUtf8(internalName)
        val superNameUtf8 = addUtf8("java/lang/Object")
        val codeUtf8 = addUtf8("Code")
        fields.forEach {
            addUtf8(it.name)
            addUtf8(it.descriptor)
        }
        methods.forEach {
            addUtf8(it.name)
            addUtf8(it.descriptor)
        }

        val thisClassCpIdx = utf8List.size + 1
        val superClassCpIdx = utf8List.size + 2
        val cpCount = utf8List.size + 3

        val out = ByteArrayOutputStream()
        fun u2(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun u4(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52) // Java 8
        u2(cpCount)
        for (s in utf8List) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(thisNameUtf8)
        out.write(7)
        u2(superNameUtf8)

        u2(0x0021) // ACC_PUBLIC | ACC_SUPER
        u2(thisClassCpIdx)
        u2(superClassCpIdx)
        u2(0) // interfaces_count

        u2(fields.size)
        for (f in fields) {
            u2(f.accessFlags)
            u2(addUtf8(f.name))
            u2(addUtf8(f.descriptor))
            u2(0)
        }

        u2(methods.size)
        for (m in methods) {
            u2(m.accessFlags)
            u2(addUtf8(m.name))
            u2(addUtf8(m.descriptor))
            u2(1)
            val codeBytes = if (m.descriptor.endsWith(")V")) {
                byteArrayOf(0xB1.toByte())
            } else {
                byteArrayOf(0x03, 0xAC.toByte())
            }
            u2(codeUtf8)
            u4(12 + codeBytes.size)
            u2(2)
            u2(4)
            u4(codeBytes.size)
            out.write(codeBytes)
            u2(0)
            u2(0)
        }
        u2(0) // class attributes_count
        return out.toByteArray()
    }

    private fun buildMinimalDexWithMethod(
        classDescriptor: String,
        methodName: String,
        returnType: String,
        accessFlags: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        // string_ids: 3 strings (classDescriptor, methodName, returnType) at offset 128
        buf.putInt(56, 3)
        buf.putInt(60, 128)
        // type_ids: 2 types (0 -> classDescriptor, 1 -> returnType) at offset 140
        buf.putInt(64, 2)
        buf.putInt(68, 140)
        // proto_ids: 1 proto at offset 148
        buf.putInt(72, 1)
        buf.putInt(76, 148)
        // field_ids: 0 fields
        buf.putInt(80, 0)
        buf.putInt(84, 0)
        // method_ids: 1 method at offset 160
        buf.putInt(88, 1)
        buf.putInt(92, 160)
        // class_defs: 1 class_def at offset 168
        buf.putInt(96, 1)
        buf.putInt(100, 168)

        // Write string_data_item entries starting at offset 220
        val strings = listOf(classDescriptor, methodName, returnType)
        var strDataPos = 220
        for ((idx, s) in strings.withIndex()) {
            buf.putInt(128 + idx * 4, strDataPos)
            val bytes = s.toByteArray(Charsets.UTF_8)
            buf.put(strDataPos++, bytes.size.toByte())
            for (b in bytes) buf.put(strDataPos++, b)
            buf.put(strDataPos++, 0)
        }

        // type_ids at 140: type[0] -> string[0], type[1] -> string[2]
        buf.putInt(140, 0)
        buf.putInt(144, 2)

        // proto_id at 148: shorty_idx=2, return_type_idx=1, parameters_off=0
        buf.putInt(148, 2)
        buf.putInt(152, 1)
        buf.putInt(156, 0)

        // method_id at 160: class_idx=0, proto_idx=0, name_idx=1
        buf.putShort(160, 0)
        buf.putShort(162, 0)
        buf.putInt(164, 1)

        // class_def at 168: class_idx=0, class_data_off=320 (offset 168 + 24 = 192)
        buf.putInt(168, 0)
        buf.putInt(192, 320)

        // class_data_item at 320: static_fields=0, instance_fields=0, direct_methods=1, virtual_methods=0
        var cpos = 320
        buf.put(cpos++, 0)
        buf.put(cpos++, 0)
        buf.put(cpos++, 1)
        buf.put(cpos++, 0)
        // encoded_method: method_idx_diff=0, access_flags=uleb128(accessFlags), code_off=0
        buf.put(cpos++, 0)
        var acc = accessFlags
        while ((acc and 0x7F.inv()) != 0) {
            buf.put(cpos++, ((acc and 0x7F) or 0x80).toByte())
            acc = acc ushr 7
        }
        buf.put(cpos++, acc.toByte())
        buf.put(cpos, 0)

        return buf.array()
    }
}
