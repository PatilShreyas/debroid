package dev.shreyaspatil.debroid.hotreload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class ClassBytecodeAnalyzerTest {

    @Test
    fun `ignores LineNumberTable shifts in hasExecutableBytecodeChange while detecting real instruction changes`() {
        val line10Bytes = buildClassWithLineNumber(returnConstant = 7, lineNumber = 10)
        val line25Bytes = buildClassWithLineNumber(returnConstant = 7, lineNumber = 25)
        val changedCodeBytes = buildClassWithLineNumber(returnConstant = 42, lineNumber = 25)

        assertTrue(!line10Bytes.contentEquals(line25Bytes))
        assertTrue(!ClassBytecodeAnalyzer.hasExecutableBytecodeChange(line10Bytes, line25Bytes))
        assertTrue(ClassBytecodeAnalyzer.hasExecutableBytecodeChange(line10Bytes, changedCodeBytes))
    }

    @Test
    fun `detects anonymous inner class hierarchy changes and remaps constant pool class names safely`() {
        val anonRunnable = buildImplementingClassWithoutBridge(
            "com/example/DigestViewModel\$uiState\$1",
            "java/lang/Runnable"
        )
        val anonCloneable = buildImplementingClassWithoutBridge(
            "com/example/DigestViewModel\$uiState\$1",
            "java/lang/Cloneable"
        )
        val inlinedClass = buildImplementingClassWithoutBridge(
            "com/example/DigestViewModel\$special\$\$inlined\$map\$1",
            "java/lang/Runnable"
        )
        val namedSealedChild = buildImplementingClassWithoutBridge(
            "com/example/QueueFilter\$All",
            "java/lang/Runnable"
        )
        val serializerClass = buildImplementingClassWithoutBridge(
            "com/example/DigestNavKey\$\$serializer",
            "java/lang/Runnable"
        )

        assertTrue(ClassBytecodeAnalyzer.isRenamableSyntheticOrAnonymousClass(anonRunnable))
        assertTrue(ClassBytecodeAnalyzer.isRenamableSyntheticOrAnonymousClass(inlinedClass))
        assertTrue(!ClassBytecodeAnalyzer.isRenamableSyntheticOrAnonymousClass(namedSealedChild))
        assertTrue(!ClassBytecodeAnalyzer.isRenamableSyntheticOrAnonymousClass(serializerClass))

        assertTrue(!ClassBytecodeAnalyzer.hasClassHierarchyChange(anonRunnable, anonRunnable))
        assertTrue(ClassBytecodeAnalyzer.hasClassHierarchyChange(anonRunnable, anonCloneable))

        val renames = mapOf(
            "com/example/Outer\$1" to "com/example/Outer\$1\$hr1",
            "com/example/Outer\$1\$2" to "com/example/Outer\$1\$hr1\$2\$hr2"
        )
        assertEquals(
            "com/example/Outer\$1\$hr1",
            ClassBytecodeAnalyzer.remapSingleInternalClassName("com/example/Outer\$1", renames)
        )
        assertEquals(
            "com/example/Outer\$1\$hr1\$3",
            ClassBytecodeAnalyzer.remapSingleInternalClassName("com/example/Outer\$1\$3", renames)
        )
        assertEquals(
            "com/example/Outer\$1\$hr1\$2\$hr2",
            ClassBytecodeAnalyzer.remapSingleInternalClassName("com/example/Outer\$1\$2", renames)
        )
        assertEquals(
            "com/example/Outer\$10",
            ClassBytecodeAnalyzer.remapSingleInternalClassName("com/example/Outer\$10", renames)
        )

        val remappedCloneable = ClassBytecodeAnalyzer.remapInternalClassNames(
            anonCloneable,
            mapOf("com/example/DigestViewModel\$uiState\$1" to "com/example/DigestViewModel\$uiState\$1\$hr1")
        )
        val loader = ByteArrayClassLoader()
        val loaded = loader.define("com.example.DigestViewModel\$uiState\$1\$hr1", remappedCloneable)
        assertEquals("com.example.DigestViewModel\$uiState\$1\$hr1", loaded.name)
        assertTrue(Cloneable::class.java.isAssignableFrom(loaded))
        assertNotNull(loaded.getDeclaredConstructor().newInstance())
    }

    @Test
    fun `synthesizes DefaultImpls from interface default method and redirects implementing class invokespecial`() {
        val ifaceBytesV1 = buildInterfaceWithDefaultMethod("com/example/UiState", returnConstant = 7)
        val ifaceBytesV2 = buildInterfaceWithDefaultMethod("com/example/UiState", returnConstant = 42)
        val implBytes = buildImplementingClassCallingInterfaceDefault(
            implInternalName = "com/example/UiState\$Loading",
            ifaceInternalName = "com/example/UiState"
        )

        assertTrue(ClassBytecodeAnalyzer.isInterfaceClass(ifaceBytesV2))
        assertTrue(!ClassBytecodeAnalyzer.hasInterfaceStructureChange(ifaceBytesV1, ifaceBytesV2))
        assertTrue(ClassBytecodeAnalyzer.hasExecutableBytecodeChange(ifaceBytesV1, ifaceBytesV2))

        val synthesizedDefaultImpls = ClassBytecodeAnalyzer.synthesizeDefaultImplsFromInterface(ifaceBytesV2)
        assertNotNull(synthesizedDefaultImpls)

        val rewrittenImpl = ClassBytecodeAnalyzer.rewriteInterfaceSpecialCallsToDefaultImpls(
            implBytes,
            setOf("com/example/UiState")
        )

        // Load V1 of the interface (returning 7), but load the synthesized V2 DefaultImpls (returning 42)
        // and the rewritten Loading implementation: calling shouldKeepSplashScreen() must return 42!
        val loader = ByteArrayClassLoader()
        loader.define("com.example.UiState", ifaceBytesV1)
        loader.define("com.example.UiState\$DefaultImpls", synthesizedDefaultImpls!!)
        val loadingCls = loader.define("com.example.UiState\$Loading", rewrittenImpl)
        val instance = loadingCls.getDeclaredConstructor().newInstance()
        val result = loadingCls.getMethod("shouldKeepSplashScreen").invoke(instance) as Int
        assertEquals(42, result)
    }

    @Test
    fun `ignores Compose sourceInformation and traceEventStart shifts and DebugMetadata while detecting real edits`() {
        val v1 = buildComposableClassWithSourceInfo(
            sourceInfo = "C(NewsFeed)140@5100L24:NewsFeed.kt#abc",
            traceInfo = "com.example.NewsFeed (NewsFeed.kt:140)",
            debugLine = 140,
            routePath = "/v1/feed",
            returnConst = 7
        )
        val v2ShiftedLines = buildComposableClassWithSourceInfo(
            sourceInfo = "C(NewsFeed)158@5680L24:NewsFeed.kt#abc",
            traceInfo = "com.example.NewsFeed (NewsFeed.kt:158)",
            debugLine = 158,
            routePath = "/v1/feed",
            returnConst = 7
        )
        val v3AnnotationChanged = buildComposableClassWithSourceInfo(
            sourceInfo = "C(NewsFeed)158@5680L24:NewsFeed.kt#abc",
            traceInfo = "com.example.NewsFeed (NewsFeed.kt:158)",
            debugLine = 158,
            routePath = "/v2/feed",
            returnConst = 7
        )

        assertTrue(!ClassBytecodeAnalyzer.hasExecutableBytecodeChange(v1, v2ShiftedLines))
        assertTrue(ClassBytecodeAnalyzer.hasExecutableBytecodeChange(v1, v3AnnotationChanged))
    }

    @Test
    fun `injects missing interface default bridges for no-compatibility mode and remaps interface lambda handles`() {
        val ifaceV1 = buildInterfaceWithDefaultMethod("com/example/NoCompatState", returnConstant = 5)
        val ifaceV2WithLambda = buildInterfaceWithLambdaDefaultMethod(
            ifaceInternalName = "com/example/NoCompatState",
            lambdaReturnConst = 88
        )
        val noCompatImpl = buildImplementingClassWithoutBridge(
            implInternalName = "com/example/NoCompatState\$Loading",
            ifaceInternalName = "com/example/NoCompatState"
        )

        val synthesizedDefaultImpls = ClassBytecodeAnalyzer.synthesizeDefaultImplsFromInterface(ifaceV2WithLambda)
        assertNotNull(synthesizedDefaultImpls)

        val defaults = ClassBytecodeAnalyzer.extractInterfaceDefaultMethods(ifaceV2WithLambda)
        assertEquals(listOf("shouldKeepSplashScreen" to "()I"), defaults)

        val bridgedImpl = ClassBytecodeAnalyzer.injectMissingInterfaceDefaultBridges(
            classBytes = noCompatImpl,
            defaultMethodsByIface = mapOf("com/example/NoCompatState" to defaults)
        )

        val loader = ByteArrayClassLoader()
        val ifaceCls = loader.define("com.example.NoCompatState", ifaceV1)
        loader.define("com.example.NoCompatState\$DefaultImpls", synthesizedDefaultImpls!!)
        val loadingCls = loader.define("com.example.NoCompatState\$Loading", bridgedImpl)
        val instance = loadingCls.getDeclaredConstructor().newInstance()
        val result = ifaceCls.getMethod("shouldKeepSplashScreen").invoke(instance) as Int
        assertEquals(88, result)
    }

    @Test
    fun `preserves Compose remember state when local variable slots shift around unchanged remember block`() {
        val v1 = buildComposableWithRememberSlot(initialConst = 12, tempLocalSlot = 3, groupKey = 777)
        val v2ShiftedSlot = buildComposableWithRememberSlot(initialConst = 12, tempLocalSlot = 7, groupKey = 777)
        val v3ChangedInit = buildComposableWithRememberSlot(initialConst = 99, tempLocalSlot = 7, groupKey = 777)

        val sameRememberDiff = ClassBytecodeAnalyzer.analyzeComposeRememberChanges(v1, v2ShiftedSlot)
        assertTrue(sameRememberDiff.resetScopeKeys.isEmpty())
        assertTrue(!sameRememberDiff.resetAllClassScopes)

        val changedRememberDiff = ClassBytecodeAnalyzer.analyzeComposeRememberChanges(v1, v3ChangedInit)
        assertEquals(setOf(777), changedRememberDiff.resetScopeKeys)
    }

    private class ByteArrayClassLoader : ClassLoader(ClassBytecodeAnalyzerTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    private fun buildClassWithLineNumber(returnConstant: Int, lineNumber: Int): ByteArray {
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
            "com/example/Sibling",
            "java/lang/Object",
            "Code",
            "LineNumberTable",
            "compute",
            "()I"
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(9)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)

        u2(0x0021)
        u2(7)
        u2(8)
        u2(0)
        u2(0)
        u2(1)
        u2(0x0009)
        u2(5)
        u2(6)
        u2(1)
        val codeBytes = byteArrayOf(0x10, returnConstant.toByte(), 0xAC.toByte())
        u2(3)
        u4(12 + codeBytes.size + 12)
        u2(1)
        u2(0)
        u4(codeBytes.size)
        out.write(codeBytes)
        u2(0)
        u2(1)
        u2(4)
        u4(6)
        u2(1)
        u2(0)
        u2(lineNumber)
        u2(0)
        return out.toByteArray()
    }

    private fun buildInterfaceWithDefaultMethod(ifaceInternalName: String, returnConstant: Int): ByteArray {
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
            ifaceInternalName,
            "java/lang/Object",
            "Code",
            "shouldKeepSplashScreen",
            "()I"
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(8)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)

        u2(0x0601)
        u2(6)
        u2(7)
        u2(0)
        u2(0)
        u2(1)
        u2(0x0001)
        u2(4)
        u2(5)
        u2(1)
        val codeBytes = byteArrayOf(0x10, returnConstant.toByte(), 0xAC.toByte())
        u2(3)
        u4(12 + codeBytes.size)
        u2(1)
        u2(1)
        u4(codeBytes.size)
        out.write(codeBytes)
        u2(0)
        u2(0)
        u2(0)
        return out.toByteArray()
    }

    private fun buildImplementingClassCallingInterfaceDefault(
        implInternalName: String,
        ifaceInternalName: String
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
            implInternalName,
            "java/lang/Object",
            ifaceInternalName,
            "Code",
            "<init>",
            "()V",
            "shouldKeepSplashScreen",
            "()I"
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(16)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)
        out.write(7)
        u2(3)
        out.write(12)
        u2(5)
        u2(6)
        out.write(10)
        u2(10)
        u2(12)
        out.write(12)
        u2(7)
        u2(8)
        out.write(11)
        u2(11)
        u2(14)

        u2(0x0021)
        u2(9)
        u2(10)
        u2(1)
        u2(11)
        u2(0)
        u2(2)
        u2(0x0001)
        u2(5)
        u2(6)
        u2(1)
        val initCode = byteArrayOf(0x2A, 0xB7.toByte(), 0x00, 13, 0xB1.toByte())
        u2(4)
        u4(12 + initCode.size)
        u2(1)
        u2(1)
        u4(initCode.size)
        out.write(initCode)
        u2(0)
        u2(0)
        u2(0x0001)
        u2(7)
        u2(8)
        u2(1)
        val bridgeCode = byteArrayOf(0x2A, 0xB7.toByte(), 0x00, 15, 0xAC.toByte())
        u2(4)
        u4(12 + bridgeCode.size)
        u2(1)
        u2(1)
        u4(bridgeCode.size)
        out.write(bridgeCode)
        u2(0)
        u2(0)

        u2(0)
        return out.toByteArray()
    }

    private fun buildComposableClassWithSourceInfo(
        sourceInfo: String,
        traceInfo: String,
        debugLine: Int,
        routePath: String,
        returnConst: Int
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
            "com/example/NewsFeedKt",
            "java/lang/Object",
            "androidx/compose/runtime/ComposerKt",
            "Code",
            "RuntimeVisibleAnnotations",
            "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
            "l",
            "Lretrofit2/http/GET;",
            "value",
            routePath,
            "render",
            "()I",
            "sourceInformation",
            "(Ljava/lang/Object;Ljava/lang/String;)V",
            "traceEventStart",
            "(IIILjava/lang/String;)V",
            sourceInfo,
            traceInfo
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(29)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)
        out.write(7)
        u2(3)
        out.write(8)
        u2(17)
        out.write(8)
        u2(18)
        out.write(12)
        u2(13)
        u2(14)
        out.write(10)
        u2(21)
        u2(24)
        out.write(12)
        u2(15)
        u2(16)
        out.write(10)
        u2(21)
        u2(26)
        out.write(3)
        u4(debugLine)

        u2(0x0021)
        u2(19)
        u2(20)
        u2(0)
        u2(0)
        u2(1)
        u2(0x0009)
        u2(11)
        u2(12)
        u2(1)
        val code = byteArrayOf(
            0x01,
            0x12,
            22,
            0xB8.toByte(),
            0x00,
            25,
            0x04,
            0x03,
            0x02,
            0x12,
            23,
            0xB8.toByte(),
            0x00,
            27,
            0x10,
            returnConst.toByte(),
            0xAC.toByte()
        )
        u2(4)
        u4(12 + code.size)
        u2(4)
        u2(0)
        u4(code.size)
        out.write(code)
        u2(0)
        u2(0)

        u2(1)
        val annBody = ByteArrayOutputStream()
        writeU2To(annBody, 2)
        writeU2To(annBody, 6)
        writeU2To(annBody, 1)
        writeU2To(annBody, 7)
        annBody.write('['.code)
        writeU2To(annBody, 1)
        annBody.write('I'.code)
        writeU2To(annBody, 28)
        writeU2To(annBody, 8)
        writeU2To(annBody, 1)
        writeU2To(annBody, 9)
        annBody.write('s'.code)
        writeU2To(annBody, 10)
        val annBytes = annBody.toByteArray()
        u2(5)
        u4(annBytes.size)
        out.write(annBytes)
        return out.toByteArray()
    }

    private fun buildImplementingClassWithoutBridge(
        implInternalName: String,
        ifaceInternalName: String
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
        val utf8s = listOf(implInternalName, "java/lang/Object", ifaceInternalName, "Code", "<init>", "()V")
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(12)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)
        out.write(7)
        u2(3)
        out.write(12)
        u2(5)
        u2(6)
        out.write(10)
        u2(8)
        u2(10)
        u2(0x0021)
        u2(7)
        u2(8)
        u2(1)
        u2(9)
        u2(0)
        u2(1)
        u2(0x0001)
        u2(5)
        u2(6)
        u2(1)
        val initCode = byteArrayOf(0x2A, 0xB7.toByte(), 0x00, 11, 0xB1.toByte())
        u2(4)
        u4(12 + initCode.size)
        u2(1)
        u2(1)
        u4(initCode.size)
        out.write(initCode)
        u2(0)
        u2(0)
        u2(0)
        return out.toByteArray()
    }

    private fun buildInterfaceWithLambdaDefaultMethod(
        ifaceInternalName: String,
        lambdaReturnConst: Int
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
            ifaceInternalName,
            "java/lang/Object",
            "java/util/function/IntSupplier",
            "java/lang/invoke/LambdaMetafactory",
            "Code",
            "BootstrapMethods",
            "shouldKeepSplashScreen",
            "()I",
            "shouldKeepSplashScreen\$lambda\$0",
            "getAsInt",
            "()Ljava/util/function/IntSupplier;",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                "Ljava/lang/invoke/CallSite;"
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(29)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1)
        out.write(7)
        u2(2)
        out.write(7)
        u2(3)
        out.write(7)
        u2(4)
        out.write(12)
        u2(12)
        u2(13)
        out.write(10)
        u2(17)
        u2(18)
        out.write(15)
        out.write(6)
        u2(19)
        out.write(16)
        u2(8)
        out.write(12)
        u2(9)
        u2(8)
        out.write(11)
        u2(14)
        u2(22)
        out.write(15)
        out.write(6)
        u2(23)
        out.write(12)
        u2(10)
        u2(11)
        out.write(18)
        u2(0)
        u2(25)
        out.write(12)
        u2(10)
        u2(8)
        out.write(11)
        u2(16)
        u2(27)

        u2(0x0601)
        u2(14)
        u2(15)
        u2(0)
        u2(0)
        u2(2)
        u2(0x0001)
        u2(7)
        u2(8)
        u2(1)
        val defaultCode = byteArrayOf(
            0xBA.toByte(),
            0x00,
            26,
            0x00,
            0x00,
            0xB9.toByte(),
            0x00,
            28,
            0x01,
            0x00,
            0xAC.toByte()
        )
        u2(5)
        u4(12 + defaultCode.size)
        u2(2)
        u2(1)
        u4(defaultCode.size)
        out.write(defaultCode)
        u2(0)
        u2(0)
        u2(0x100A)
        u2(9)
        u2(8)
        u2(1)
        val lambdaCode = byteArrayOf(0x10, lambdaReturnConst.toByte(), 0xAC.toByte())
        u2(5)
        u4(12 + lambdaCode.size)
        u2(1)
        u2(0)
        u4(lambdaCode.size)
        out.write(lambdaCode)
        u2(0)
        u2(0)

        u2(1)
        u2(6)
        u4(12)
        u2(1)
        u2(20)
        u2(3)
        u2(21)
        u2(24)
        u2(21)
        return out.toByteArray()
    }

    private fun writeU2To(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun buildComposableWithRememberSlot(
        initialConst: Int,
        tempLocalSlot: Int,
        groupKey: Int
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
            "com/example/PulseCardKt", // 1
            "java/lang/Object", // 2
            "androidx/compose/runtime/Composer", // 3
            "Code", // 4
            "PulseCard", // 5
            "(Landroidx/compose/runtime/Composer;I)V", // 6
            "startRestartGroup", // 7
            "(I)Landroidx/compose/runtime/Composer;", // 8
            "rememberedValue", // 9
            "()Ljava/lang/Object;", // 10
            "updateRememberedValue", // 11
            "(Ljava/lang/Object;)V", // 12
            "valueOf", // 13
            "(I)Ljava/lang/Integer;", // 14
            "java/lang/Integer" // 15
        )
        u4(0xCAFEBABE.toInt())
        u2(0)
        u2(52)
        u2(27)
        for (s in utf8s) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write(1)
            u2(b.size)
            out.write(b)
        }
        out.write(7)
        u2(1) // #16 PulseCardKt
        out.write(7)
        u2(2) // #17 Object
        out.write(7)
        u2(3) // #18 Composer
        out.write(7)
        u2(15) // #19 Integer
        out.write(12)
        u2(7)
        u2(8) // #20 startRestartGroup
        out.write(11)
        u2(18)
        u2(20) // #21 Composer.startRestartGroup
        out.write(12)
        u2(9)
        u2(10) // #22 rememberedValue
        out.write(11)
        u2(18)
        u2(22) // #23 Composer.rememberedValue
        out.write(12)
        u2(11)
        u2(12) // #24 updateRememberedValue
        out.write(11)
        u2(18)
        u2(24) // #25 Composer.updateRememberedValue
        out.write(12)
        u2(13)
        u2(14) // #26 valueOf:(I)Ljava/lang/Integer;
        // Wait: need Methodref #19.#26 -> cpCount = 28
        val patchedOut = ByteArrayOutputStream()
        val headerAndCp = out.toByteArray()
        // Update constant_pool_count at offset 8..9 from 27 -> 28
        headerAndCp[8] = 0
        headerAndCp[9] = 28
        patchedOut.write(headerAndCp)
        patchedOut.write(10)
        writeU2To(patchedOut, 19)
        writeU2To(patchedOut, 26) // #27 Integer.valueOf

        val storeBytes = if (tempLocalSlot <= 3) {
            byteArrayOf((0x4B + tempLocalSlot).toByte()) // astore_0..3
        } else {
            byteArrayOf(0x3A, tempLocalSlot.toByte()) // astore N
        }
        val loadBytes = if (tempLocalSlot <= 3) {
            byteArrayOf((0x2A + tempLocalSlot).toByte()) // aload_0..3
        } else {
            byteArrayOf(0x19, tempLocalSlot.toByte()) // aload N
        }

        val codeBuf = ByteArrayOutputStream()
        // aload_0; sipush groupKey; invokeinterface #21 (2, 0); astore_0
        codeBuf.write(
            byteArrayOf(
                0x2A,
                0x11,
                ((groupKey ushr 8) and 0xFF).toByte(),
                (groupKey and 0xFF).toByte(),
                0xB9.toByte(),
                0x00,
                21,
                0x02,
                0x00,
                0x4B
            )
        )
        // aload_0; invokeinterface #23 rememberedValue (1, 0); pop
        codeBuf.write(byteArrayOf(0x2A, 0xB9.toByte(), 0x00, 23, 0x01, 0x00, 0x57))
        // Push initialConst, box via Integer.valueOf, store/load temp slot, and call updateRememberedValue
        codeBuf.write(byteArrayOf(0x10, initialConst.toByte(), 0xB8.toByte(), 0x00, 27))
        codeBuf.write(storeBytes)
        codeBuf.write(byteArrayOf(0x2A))
        codeBuf.write(loadBytes)
        codeBuf.write(byteArrayOf(0xB9.toByte(), 0x00, 25, 0x02, 0x00, 0xB1.toByte()))
        val codeBytes = codeBuf.toByteArray()

        writeU2To(patchedOut, 0x0021)
        writeU2To(patchedOut, 16)
        writeU2To(patchedOut, 17)
        writeU2To(patchedOut, 0)
        writeU2To(patchedOut, 0)
        writeU2To(patchedOut, 1)
        writeU2To(patchedOut, 0x0009) // public static
        writeU2To(patchedOut, 5)
        writeU2To(patchedOut, 6)
        writeU2To(patchedOut, 1)
        writeU2To(patchedOut, 4)
        val attrLen = 12 + codeBytes.size
        patchedOut.write((attrLen ushr 24) and 0xFF)
        patchedOut.write((attrLen ushr 16) and 0xFF)
        patchedOut.write((attrLen ushr 8) and 0xFF)
        patchedOut.write(attrLen and 0xFF)
        writeU2To(patchedOut, 4)
        writeU2To(patchedOut, tempLocalSlot + 2)
        patchedOut.write((codeBytes.size ushr 24) and 0xFF)
        patchedOut.write((codeBytes.size ushr 16) and 0xFF)
        patchedOut.write((codeBytes.size ushr 8) and 0xFF)
        patchedOut.write(codeBytes.size and 0xFF)
        patchedOut.write(codeBytes)
        writeU2To(patchedOut, 0)
        writeU2To(patchedOut, 0)
        writeU2To(patchedOut, 0)
        return patchedOut.toByteArray()
    }
}
