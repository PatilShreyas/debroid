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

    @Test
    fun `transforms Hilt AndroidEntryPoint class superclass and super calls to Hilt_ base class`() {
        val hiltBaseBytes = buildSyntheticClassFile(
            internalName = "com/example/Hilt_MainActivity",
            superName = "java/lang/Object",
            fields = emptyList(),
            methods = emptyList(),
            includeDefaultConstructor = true
        )
        val unweavedActivityBytes = buildSyntheticClassFile(
            internalName = "com/example/MainActivity",
            superName = "java/lang/Object",
            fields = emptyList(),
            methods = listOf(AdditiveClassMerger.MemberSignature(0x0001, "activeMethod", "()I")),
            includeDefaultConstructor = true,
            includeAndroidEntryPointAnnotation = true
        )

        val transformedBytes = ClassBytecodeAnalyzer.applyHiltTransformIfNeeded(
            classBytes = unweavedActivityBytes,
            baselineSuperClass = null,
            hasHiltSiblingClass = false
        )

        val loader = ByteArrayClassLoader()
        val hiltBaseClass = loader.define("com.example.Hilt_MainActivity", hiltBaseBytes)
        val activityClass = loader.define("com.example.MainActivity", transformedBytes)

        assertEquals(hiltBaseClass, activityClass.superclass)
        val instance = activityClass.getDeclaredConstructor().newInstance()
        assertTrue(hiltBaseClass.isInstance(instance))
    }

    @Test
    fun `detects remember call changes per Composable and maps to restart group keys`() {
        val prevBytes = buildComposableClassWithRememberCalls(
            screenARememberTypes = listOf("I"),
            screenBRememberTypes = listOf("I", "Ljava/lang/String;")
        )
        val currBytes = buildComposableClassWithRememberCalls(
            screenARememberTypes = listOf("I"),
            screenBRememberTypes = listOf("Ljava/lang/String;", "I")
        )

        val diff = ClassBytecodeAnalyzer.analyzeComposeRememberChanges(prevBytes, currBytes)
        // ScreenA (key 101) is unchanged; ScreenB (key 202) reordered its remember calls
        assertEquals(setOf(202), diff.resetScopeKeys)
    }

    @Test
    fun `diffs ConstantValue static fields and patches clinit to preserve unchanged static fields`() {
        val prevBytes = buildClassWithStaticFieldsAndClinit(
            versionConstant = 1,
            firstStaticValue = 10,
            secondStaticValue = 20
        )
        val currBytes = buildClassWithStaticFieldsAndClinit(
            versionConstant = 2,
            firstStaticValue = 10,
            secondStaticValue = 99
        )

        val staticDiff = ClassBytecodeAnalyzer.prepareStaticFieldUpdates(prevBytes, currBytes)
        assertTrue(staticDiff.reinitClinit)
        assertEquals(
            listOf(
                StaticFieldUpdate(
                    className = "com.example.StaticHolder",
                    fieldName = "VERSION",
                    descriptor = "I",
                    value = "2"
                )
            ),
            staticDiff.constantFieldUpdates
        )

        val loadedClass = ByteArrayClassLoader().define("com.example.StaticHolder", staticDiff.patchedClassBytes)
        // In the patched <clinit>, FIRST_STATE (unchanged: 10) was NOP'd out so it stays 0 when loaded fresh,
        // while SECOND_STATE (changed: 20 -> 99) is assigned 99!
        assertEquals(0, loadedClass.getDeclaredField("FIRST_STATE").getInt(null))
        assertEquals(99, loadedClass.getDeclaredField("SECOND_STATE").getInt(null))
    }

    @Test
    fun `preserves removed constructor stubs for Object and ViewModel subclasses when adding fields`() {
        val prevClassBytes = buildSyntheticClassFile(
            internalName = "com/example/NavItem",
            fields = listOf(AdditiveClassMerger.MemberSignature(0x0012, "iconId", "I")),
            methods = listOf(AdditiveClassMerger.MemberSignature(0x0001, "<init>", "(I)V"))
        )
        val currClassBytes = buildSyntheticClassFile(
            internalName = "com/example/NavItem",
            fields = listOf(
                AdditiveClassMerger.MemberSignature(0x0012, "iconId", "I"),
                AdditiveClassMerger.MemberSignature(0x0012, "customLabel", "Ljava/lang/String;")
            ),
            methods = emptyList(),
            includeDefaultConstructor = true
        )

        val mergedBytes = AdditiveClassMerger.mergePreservingRemovedMembers(prevClassBytes, currClassBytes)
        val loadedClass = ByteArrayClassLoader().define("com.example.NavItem", mergedBytes)
        val legacyCtor = loadedClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
        assertNotNull(legacyCtor.newInstance(42))

        // Arbitrary non-Object/non-ViewModel base classes must not synthesize super.<init>()V stubs
        val customSubCurr = buildSyntheticClassFile(
            internalName = "com/example/CustomSub",
            superName = "com/example/CustomBase",
            fields = emptyList(),
            methods = emptyList()
        )
        val customPrevDex = AdditiveClassMerger.DexClassMembers(
            fields = emptyList(),
            methods = listOf(AdditiveClassMerger.MemberSignature(0x0001, "<init>", "(I)V"))
        )
        val customCurrDex = AdditiveClassMerger.DexClassMembers(fields = emptyList(), methods = emptyList())
        val unmergedCustom = AdditiveClassMerger.mergeWithPreviousDexMembers(
            prevDex = customPrevDex,
            currDex = customCurrDex,
            currentClassBytes = customSubCurr
        )
        assertTrue(unmergedCustom.contentEquals(customSubCurr))
    }

    private class ByteArrayClassLoader : ClassLoader(AdditiveClassMergerTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    @Suppress("LongParameterList")
    private fun buildSyntheticClassFile(
        internalName: String,
        fields: List<AdditiveClassMerger.MemberSignature>,
        methods: List<AdditiveClassMerger.MemberSignature>,
        superName: String = "java/lang/Object",
        includeDefaultConstructor: Boolean = false,
        includeAndroidEntryPointAnnotation: Boolean = false
    ): ByteArray {
        val utf8List = mutableListOf<String>()
        fun addUtf8(str: String): Int {
            val existing = utf8List.indexOf(str)
            if (existing >= 0) return existing + 1
            utf8List.add(str)
            return utf8List.size
        }

        val thisNameUtf8 = addUtf8(internalName)
        val superNameUtf8 = addUtf8(superName)
        val codeUtf8 = addUtf8("Code")
        val initNameUtf8 = addUtf8("<init>")
        val voidDescUtf8 = addUtf8("()V")
        val rvaUtf8 = if (includeAndroidEntryPointAnnotation) addUtf8("RuntimeVisibleAnnotations") else 0
        val hiltAnnUtf8 = if (includeAndroidEntryPointAnnotation) {
            addUtf8("Ldagger/hilt/android/AndroidEntryPoint;")
        } else {
            0
        }
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
        val initNatCpIdx = utf8List.size + 3
        val superInitRefCpIdx = utf8List.size + 4
        val cpCount = utf8List.size + 5

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
        out.write(12) // CONSTANT_NameAndType <init>:()V
        u2(initNameUtf8)
        u2(voidDescUtf8)
        out.write(10) // CONSTANT_Methodref super.<init>:()V
        u2(superClassCpIdx)
        u2(initNatCpIdx)

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

        val totalMethods = methods.size + if (includeDefaultConstructor) 1 else 0
        u2(totalMethods)
        if (includeDefaultConstructor) {
            u2(0x0001) // public <init>()V
            u2(initNameUtf8)
            u2(voidDescUtf8)
            u2(1)
            // aload_0; invokespecial super.<init>; return
            val initCode = byteArrayOf(
                0x2A,
                0xB7.toByte(),
                ((superInitRefCpIdx ushr 8) and 0xFF).toByte(),
                (superInitRefCpIdx and 0xFF).toByte(),
                0xB1.toByte()
            )
            u2(codeUtf8)
            u4(12 + initCode.size)
            u2(2)
            u2(2)
            u4(initCode.size)
            out.write(initCode)
            u2(0)
            u2(0)
        }
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
        if (includeAndroidEntryPointAnnotation) {
            u2(1) // 1 class attribute
            u2(rvaUtf8)
            u4(6) // 2 (num_annotations) + 2 (type_index) + 2 (num_element_value_pairs)
            u2(1)
            u2(hiltAnnUtf8)
            u2(0)
        } else {
            u2(0) // class attributes_count
        }
        return out.toByteArray()
    }

    private fun buildComposableClassWithRememberCalls(
        screenARememberTypes: List<String>,
        screenBRememberTypes: List<String>
    ): ByteArray {
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

        // Constant pool entries:
        // 1: Utf8 "com/example/ScreensKt"
        // 2: Utf8 "java/lang/Object"
        // 3: Utf8 "Code"
        // 4: Utf8 "ScreenA"
        // 5: Utf8 "ScreenB"
        // 6: Utf8 "(Landroidx/compose/runtime/Composer;I)V"
        // 7: Utf8 "androidx/compose/runtime/Composer"
        // 8: Utf8 "startRestartGroup"
        // 9: Utf8 "(I)Landroidx/compose/runtime/Composer;"
        // 10: Utf8 "rememberedValue"
        // 11: Utf8 "()Ljava/lang/Object;"
        // 12: Utf8 "updateRememberedValue"
        // 13: Utf8 "(Ljava/lang/Object;)V"
        // 14: Class #1 (com/example/ScreensKt)
        // 15: Class #2 (java/lang/Object)
        // 16: Class #7 (androidx/compose/runtime/Composer)
        // 17: NameAndType #8:#9 (startRestartGroup)
        // 18: NameAndType #10:#11 (rememberedValue)
        // 19: NameAndType #12:#13 (updateRememberedValue)
        // 20: InterfaceMethodref #16.#17 (Composer.startRestartGroup)
        // 21: InterfaceMethodref #16.#18 (Composer.rememberedValue)
        // 22: InterfaceMethodref #16.#19 (Composer.updateRememberedValue)
        // 23: Utf8 "I"
        // 24: Utf8 "Ljava/lang/String;"
        // 25: Class #23
        // 26: Class #24
        val utf8s = listOf(
            "com/example/ScreensKt",
            "java/lang/Object",
            "Code",
            "ScreenA",
            "ScreenB",
            "(Landroidx/compose/runtime/Composer;I)V",
            "androidx/compose/runtime/Composer",
            "startRestartGroup",
            "(I)Landroidx/compose/runtime/Composer;",
            "rememberedValue",
            "()Ljava/lang/Object;",
            "updateRememberedValue",
            "(Ljava/lang/Object;)V"
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(27) // cpCount = 26 + 1
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1) // #14
        out.write(7)
        u2(2) // #15
        out.write(7)
        u2(7) // #16
        out.write(12)
        u2(8)
        u2(9) // #17
        out.write(12)
        u2(10)
        u2(11) // #18
        out.write(12)
        u2(12)
        u2(13) // #19
        out.write(11)
        u2(16)
        u2(17) // #20 startRestartGroup
        out.write(11)
        u2(16)
        u2(18) // #21 rememberedValue
        out.write(11)
        u2(16)
        u2(19) // #22 updateRememberedValue
        val iBytes = "java/lang/Integer".toByteArray(Charsets.UTF_8)
        out.write(1)
        u2(iBytes.size)
        out.write(iBytes) // #23
        val sBytes = "java/lang/String".toByteArray(Charsets.UTF_8)
        out.write(1)
        u2(sBytes.size)
        out.write(sBytes) // #24
        out.write(7)
        u2(23) // #25 Integer class
        out.write(7)
        u2(24) // #26 String class

        u2(0x0011)
        u2(14)
        u2(15)
        u2(0)
        u2(0) // fields

        fun buildComposableCode(groupKey: Int, rememberTypes: List<String>): ByteArray {
            val code = ByteArrayOutputStream()
            // aload_0; sipush groupKey; invokeinterface #20, 2, 0; pop
            code.write(0x2A)
            code.write(0x11)
            code.write((groupKey ushr 8) and 0xFF)
            code.write(groupKey and 0xFF)
            code.write(0xB9)
            code.write(0)
            code.write(20)
            code.write(2)
            code.write(0)
            code.write(0x57)
            for (t in rememberTypes) {
                // aload_0, invokeinterface rememberedValue (#21), checkcast, updateRememberedValue (#22)
                val clsCp = if (t == "I") 25 else 26
                code.write(0x2A)
                code.write(0xB9)
                code.write(0)
                code.write(21)
                code.write(1)
                code.write(0)
                code.write(0xC0)
                code.write(0)
                code.write(clsCp)
                code.write(0x2A)
                code.write(0x5F)
                code.write(0xB9)
                code.write(0)
                code.write(22)
                code.write(2)
                code.write(0)
            }
            code.write(0xB1) // return
            return code.toByteArray()
        }

        u2(2) // 2 methods: ScreenA, ScreenB
        for ((nameIdx, pair) in listOf(4 to (101 to screenARememberTypes), 5 to (202 to screenBRememberTypes))) {
            val codeBytes = buildComposableCode(pair.first, pair.second)
            u2(0x0009)
            u2(nameIdx)
            u2(6)
            u2(1)
            u2(3) // Code
            u4(12 + codeBytes.size)
            u2(4)
            u2(4)
            u4(codeBytes.size)
            out.write(codeBytes)
            u2(0)
            u2(0)
        }
        u2(0)
        return out.toByteArray()
    }

    private fun buildClassWithStaticFieldsAndClinit(
        versionConstant: Int,
        firstStaticValue: Int,
        secondStaticValue: Int
    ): ByteArray {
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

        val utf8s = listOf(
            "com/example/StaticHolder", // 1
            "java/lang/Object", // 2
            "Code", // 3
            "ConstantValue", // 4
            "VERSION", // 5
            "FIRST_STATE", // 6
            "SECOND_STATE", // 7
            "I", // 8
            "<clinit>", // 9
            "()V" // 10
        )
        // #11: Class #1
        // #12: Class #2
        // #13: Integer(versionConstant)
        // #14: NameAndType #6:#8 (FIRST_STATE:I)
        // #15: NameAndType #7:#8 (SECOND_STATE:I)
        // #16: Fieldref #11.#14
        // #17: Fieldref #11.#15
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(18)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1) // #11
        out.write(7)
        u2(2) // #12
        out.write(3)
        u4(versionConstant) // #13 CONSTANT_Integer
        out.write(12)
        u2(6)
        u2(8) // #14
        out.write(12)
        u2(7)
        u2(8) // #15
        out.write(9)
        u2(11)
        u2(14) // #16 Fieldref FIRST_STATE
        out.write(9)
        u2(11)
        u2(15) // #17 Fieldref SECOND_STATE

        u2(0x0021)
        u2(11)
        u2(12)
        u2(0)

        // 3 fields: VERSION (with ConstantValue), FIRST_STATE, SECOND_STATE
        u2(3)
        // VERSION
        u2(0x0019) // public static final
        u2(5)
        u2(8)
        u2(1) // 1 field attribute: ConstantValue
        u2(4)
        u4(2)
        u2(13)
        // FIRST_STATE
        u2(0x0009) // public static
        u2(6)
        u2(8)
        u2(0)
        // SECOND_STATE
        u2(0x0009) // public static
        u2(7)
        u2(8)
        u2(0)

        // 1 method: <clinit>()V
        u2(1)
        u2(0x0008) // static
        u2(9)
        u2(10)
        u2(1)
        // bipush firstStaticValue; putstatic #16; bipush secondStaticValue; putstatic #17; return
        val clinitCode = byteArrayOf(
            0x10,
            firstStaticValue.toByte(),
            0xB3.toByte(),
            0x00,
            16,
            0x10,
            secondStaticValue.toByte(),
            0xB3.toByte(),
            0x00,
            17,
            0xB1.toByte()
        )
        u2(3)
        u4(12 + clinitCode.size)
        u2(2)
        u2(0)
        u4(clinitCode.size)
        out.write(clinitCode)
        u2(0)
        u2(0)

        u2(0)
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
