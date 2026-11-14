package dev.shreyaspatil.debroid.hotreload

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.Locale

@Suppress(
    "MagicNumber",
    "TooManyFunctions",
    "LargeClass",
    "CyclomaticComplexMethod",
    "LongMethod",
    "ReturnCount",
    "ComplexCondition",
    "LongParameterList"
)
internal object ClassBytecodeAnalyzer {
    data class ComposeRememberDiff(
        val resetScopeKeys: Set<Int> = emptySet(),
        val resetAllClassScopes: Boolean = false,
        val changedScopeKeys: Set<Int> = emptySet(),
        val invalidateAllClassScopes: Boolean = true
    )

    data class StaticFieldDiffResult(
        val patchedClassBytes: ByteArray,
        val reinitClinit: Boolean,
        val constantFieldUpdates: List<StaticFieldUpdate>
    )
    private const val CLASS_MAGIC = 0xCAFEBABE.toInt()
    private const val MIN_CLASS_BYTES = 24
    private const val U16_MASK = 0xFFFF

    private const val CP_TAG_UTF8 = 1
    private const val CP_TAG_INTEGER = 3
    private const val CP_TAG_FLOAT = 4
    private const val CP_TAG_LONG = 5
    private const val CP_TAG_DOUBLE = 6
    private const val CP_TAG_CLASS = 7
    private const val CP_TAG_STRING = 8
    private const val CP_TAG_FIELDREF = 9
    private const val CP_TAG_METHODREF = 10
    private const val CP_TAG_INTERFACE_METHODREF = 11
    private const val CP_TAG_NAME_AND_TYPE = 12
    private const val CP_TAG_METHOD_HANDLE = 15
    private const val CP_TAG_METHOD_TYPE = 16
    private const val CP_TAG_DYNAMIC = 17
    private const val CP_TAG_INVOKE_DYNAMIC = 18
    private const val CP_TAG_MODULE = 19
    private const val CP_TAG_PACKAGE = 20

    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PRIVATE = 0x0002
    private const val ACC_STATIC = 0x0008
    private const val ACC_FINAL = 0x0010
    private const val ACC_SUPER = 0x0020
    private const val ACC_INTERFACE = 0x0200
    private const val ACC_ABSTRACT = 0x0400
    private const val ACC_SYNTHETIC = 0x1000
    private const val ACC_ANNOTATION = 0x2000
    private const val ACC_ENUM = 0x4000

    private const val COMPOSER_DESC = "Landroidx/compose/runtime/Composer;"
    private const val COMPOSER_OWNER = "androidx/compose/runtime/Composer"
    private const val COMPOSER_KT_OWNER = "androidx/compose/runtime/ComposerKt"
    private const val COMPOSABLE_LAMBDA_KT = "androidx/compose/runtime/internal/ComposableLambdaKt"
    private const val STATE_DESC_PREFIX = "(Landroidx/compose/runtime/State;)"
    private const val FLOW_PKG_MARKER = "Lkotlinx/coroutines/flow/"
    private const val MH_REF_INVOKE_STATIC = 6
    private val anonymousClassSuffixRegex = Regex("""\$\d+""")
    private val renamableInnerClassRegex = Regex("""(?:\$\d+(?:\$|$)|\$\${'$'}inlined\$)""")
    private val hiltAnnotations = setOf(
        "Ldagger/hilt/android/AndroidEntryPoint;",
        "Ldagger/hilt/android/HiltAndroidApp;"
    )
    private val ignoredDebugAnnotations = setOf(
        "Lkotlin/Metadata;",
        "Lkotlin/coroutines/jvm/internal/DebugMetadata;",
        "Lkotlin/jvm/internal/SourceDebugExtension;"
    )
    private val composeSourceInfoMethods = setOf(
        "sourceInformation",
        "sourceInformationMarkerStart",
        "traceEventStart"
    )
    private val composableLambdaFactories = setOf(
        "rememberComposableLambda",
        "composableLambda",
        "composableLambdaInstance"
    )

    private class ParsedField(
        val accessFlags: Int,
        val name: String,
        val descriptor: String,
        val constantValue: String?,
        val visibleAnnotationsSig: String = ""
    )

    private class ParsedMethod(
        val accessFlags: Int,
        val name: String,
        val descriptor: String,
        val nameCpIdx: Int = 0,
        val codeBytes: ByteArray?,
        val codeOffsetInClass: Int,
        val codeAttrStartOffset: Int = -1,
        val codeAttrEndOffset: Int = -1,
        val hasInlineMarker: Boolean,
        val hasStackMapTable: Boolean,
        val exceptionRanges: List<IntRange>,
        val exceptionTableSig: String = "",
        val visibleAnnotationsSig: String = ""
    )

    private class DetailedClass(
        val rawBytes: ByteArray,
        val cpCount: Int,
        val cpStartOffset: Int,
        val cpEndOffset: Int,
        val utf8Pool: Map<Int, String>,
        val classNameIdxByCp: Map<Int, Int>,
        val classNameOffsetByCp: Map<Int, Int>,
        val constValByCp: Map<Int, String>,
        val memberRefByCp: Map<Int, Triple<Int, Int, Int>>,
        val nameAndTypeByCp: Map<Int, Pair<Int, Int>>,
        val invokeDynamicByCp: Map<Int, Pair<Int, Int>>,
        val methodHandleByCp: Map<Int, Pair<Int, Int>>,
        val accessFlags: Int,
        val thisClassCpIdx: Int,
        val thisInternalName: String,
        val superClassCpIdx: Int,
        val superInternalName: String,
        val interfaces: List<String>,
        val fields: List<ParsedField>,
        val methods: List<ParsedMethod>,
        val methodsCountOffset: Int = -1,
        val methodsEndOffset: Int = -1,
        val bootstrapTargets: Map<Int, Pair<String, String>>,
        val bootstrapArgsSummary: Map<Int, String> = emptyMap(),
        val bootstrapAttrStartOffset: Int = -1,
        val bootstrapAttrEndOffset: Int = -1,
        val visibleAnnotationsSig: String = "",
        val hasHiltAnnotation: Boolean
    ) {
        fun resolveClassName(cpIdx: Int): String {
            val nameIdx = classNameIdxByCp[cpIdx] ?: return ""
            return utf8Pool[nameIdx].orEmpty()
        }

        fun resolveMemberRef(cpIdx: Int): Triple<String, String, String>? {
            val ref = memberRefByCp[cpIdx] ?: return null
            val owner = resolveClassName(ref.second)
            val nat = nameAndTypeByCp[ref.third] ?: return null
            val name = utf8Pool[nat.first].orEmpty()
            val desc = utf8Pool[nat.second].orEmpty()
            return Triple(owner, name, desc)
        }
    }

    private data class BytecodeInsn(
        val pc: Int,
        val length: Int,
        val opcode: Int,
        val operand: Int = 0,
        val intConstant: Int? = null
    )

    fun applyHiltTransformIfNeeded(
        classBytes: ByteArray,
        baselineSuperClass: String? = null,
        hasHiltSiblingClass: Boolean = false
    ): ByteArray {
        val parsed = runCatching { parseDetailedClass(classBytes) }.getOrNull() ?: return classBytes
        val thisName = parsed.thisInternalName
        val currSuper = parsed.superInternalName
        if (thisName.isEmpty() || currSuper.isEmpty()) return classBytes

        val normalizedBaselineSuper = baselineSuperClass
            ?.removeSurrounding("L", ";")
            ?.replace('.', '/')
            ?.takeIf { it.isNotEmpty() }

        val targetSuper = when {
            normalizedBaselineSuper != null &&
                normalizedBaselineSuper != currSuper &&
                isHiltSuperForClass(thisName, normalizedBaselineSuper) -> normalizedBaselineSuper
            (parsed.hasHiltAnnotation || hasHiltSiblingClass) &&
                !isHiltSuperForClass(thisName, currSuper) -> computeHiltSuperInternalName(thisName)
            else -> null
        } ?: return classBytes

        if (targetSuper == currSuper) return classBytes
        val superNameOffset = parsed.classNameOffsetByCp[parsed.superClassCpIdx] ?: return classBytes

        val existingUtf8Idx = parsed.utf8Pool.entries.firstOrNull { it.value == targetSuper }?.key
        if (existingUtf8Idx != null) {
            val cloned = classBytes.clone()
            cloned[superNameOffset] = ((existingUtf8Idx ushr 8) and 0xFF).toByte()
            cloned[superNameOffset + 1] = (existingUtf8Idx and 0xFF).toByte()
            return cloned
        }

        val newUtf8Idx = parsed.cpCount
        val encodedSuper = targetSuper.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(classBytes.size + encodedSuper.size + 8)
        // Write magic, minor, major
        out.write(classBytes, 0, 8)
        // Write constant_pool_count = cpCount + 1
        writeU2(out, parsed.cpCount + 1)
        // Copy constant pool, patching superClassCpIdx's name_index to newUtf8Idx
        val cpBytes = classBytes.copyOfRange(parsed.cpStartOffset, parsed.cpEndOffset)
        val relSuperOffset = superNameOffset - parsed.cpStartOffset
        cpBytes[relSuperOffset] = ((newUtf8Idx ushr 8) and 0xFF).toByte()
        cpBytes[relSuperOffset + 1] = (newUtf8Idx and 0xFF).toByte()
        out.write(cpBytes)
        // Append new CONSTANT_Utf8 entry for targetSuper
        out.write(CP_TAG_UTF8)
        writeU2(out, encodedSuper.size)
        out.write(encodedSuper)
        // Copy remainder of class file
        out.write(classBytes, parsed.cpEndOffset, classBytes.size - parsed.cpEndOffset)
        return out.toByteArray()
    }

    internal fun computeHiltSuperInternalName(thisInternalName: String): String {
        val pkg = thisInternalName.substringBeforeLast('/', "")
        val simple = thisInternalName.substringAfterLast('/').replace('$', '_')
        return if (pkg.isEmpty()) "Hilt_$simple" else "$pkg/Hilt_$simple"
    }

    private fun isHiltSuperForClass(thisInternalName: String, candidateSuper: String): Boolean {
        val expected = computeHiltSuperInternalName(thisInternalName)
        if (candidateSuper == expected) return true
        val simpleCandidate = candidateSuper.substringAfterLast('/')
        return simpleCandidate.startsWith("Hilt_")
    }

    fun hasCrossModuleVisibleChange(previousBytes: ByteArray?, currentBytes: ByteArray): Boolean {
        val curr = runCatching { parseDetailedClass(currentBytes) }.getOrNull() ?: return true
        if (isInternalSyntheticClass(curr)) return false
        if (previousBytes == null) return true
        val prev = runCatching { parseDetailedClass(previousBytes) }.getOrNull() ?: return true

        if (prev.accessFlags != curr.accessFlags ||
            prev.thisInternalName != curr.thisInternalName ||
            prev.superInternalName != curr.superInternalName ||
            prev.interfaces != curr.interfaces
        ) {
            return true
        }

        val prevNonPrivateFields = prev.fields
            .filter { (it.accessFlags and ACC_PRIVATE) == 0 && (it.accessFlags and ACC_SYNTHETIC) == 0 }
            .map { Triple(it.accessFlags, "${it.name}:${it.descriptor}", it.constantValue) }
        val currNonPrivateFields = curr.fields
            .filter { (it.accessFlags and ACC_PRIVATE) == 0 && (it.accessFlags and ACC_SYNTHETIC) == 0 }
            .map { Triple(it.accessFlags, "${it.name}:${it.descriptor}", it.constantValue) }
        if (prevNonPrivateFields != currNonPrivateFields) {
            return true
        }

        // Also check if any private const field changed when the class has inline functions
        val hasAnyInlineMethod = curr.methods.any { it.hasInlineMarker } || prev.methods.any { it.hasInlineMarker }
        if (hasAnyInlineMethod) {
            val prevConsts = prev.fields.mapNotNull { f -> f.constantValue?.let { "${f.name}:${f.descriptor}=$it" } }
            val currConsts = curr.fields.mapNotNull { f -> f.constantValue?.let { "${f.name}:${f.descriptor}=$it" } }
            if (prevConsts != currConsts) return true
        }

        val prevNonPrivateMethods = prev.methods
            .filter { isCrossModuleVisibleMethod(it) }
            .associateBy { it.name to it.descriptor }
        val currNonPrivateMethods = curr.methods
            .filter { isCrossModuleVisibleMethod(it) }
            .associateBy { it.name to it.descriptor }

        if (prevNonPrivateMethods.keys != currNonPrivateMethods.keys) {
            return true
        }

        for ((key, currMethod) in currNonPrivateMethods) {
            val prevMethod = prevNonPrivateMethods[key] ?: return true
            if (prevMethod.accessFlags != currMethod.accessFlags) return true
            val isInlineOrDefaultBridge = currMethod.hasInlineMarker ||
                prevMethod.hasInlineMarker ||
                currMethod.name.endsWith("\$default")
            if (isInlineOrDefaultBridge) {
                val prevSig = normalizeMethodInstructions(prev, prevMethod)
                val currSig = normalizeMethodInstructions(curr, currMethod)
                if (prevSig != currSig) return true
            }
        }

        return false
    }

    private fun isInternalSyntheticClass(parsed: DetailedClass): Boolean {
        if ((parsed.accessFlags and ACC_SYNTHETIC) != 0) return true
        val simpleName = parsed.thisInternalName.substringAfterLast('/')
        return simpleName.startsWith("ComposableSingletons\$") ||
            simpleName.endsWith("\$WhenMappings") ||
            anonymousClassSuffixRegex.containsMatchIn(simpleName)
    }

    private fun isCrossModuleVisibleMethod(method: ParsedMethod): Boolean {
        if ((method.accessFlags and ACC_PRIVATE) != 0 || method.name == "<clinit>") return false
        if ((method.accessFlags and ACC_SYNTHETIC) != 0 && !method.name.endsWith("\$default")) return false
        return true
    }

    fun hasExecutableBytecodeChange(previousBytes: ByteArray?, currentBytes: ByteArray): Boolean {
        if (previousBytes == null) return true
        if (previousBytes.contentEquals(currentBytes)) return false
        val prev = runCatching { parseDetailedClass(previousBytes) }.getOrNull() ?: return true
        val curr = runCatching { parseDetailedClass(currentBytes) }.getOrNull() ?: return true

        if (prev.accessFlags != curr.accessFlags ||
            prev.thisInternalName != curr.thisInternalName ||
            prev.superInternalName != curr.superInternalName ||
            prev.interfaces != curr.interfaces ||
            prev.visibleAnnotationsSig != curr.visibleAnnotationsSig
        ) {
            return true
        }

        val prevFields = prev.fields.map {
            listOf(it.accessFlags, it.name, it.descriptor, it.constantValue, it.visibleAnnotationsSig)
        }
        val currFields = curr.fields.map {
            listOf(it.accessFlags, it.name, it.descriptor, it.constantValue, it.visibleAnnotationsSig)
        }
        if (prevFields != currFields) return true

        if (prev.methods.size != curr.methods.size) return true
        val prevMethods = prev.methods.associateBy { it.name to it.descriptor }
        val currMethods = curr.methods.associateBy { it.name to it.descriptor }
        if (prevMethods.keys != currMethods.keys) return true

        for ((key, currMethod) in currMethods) {
            val prevMethod = prevMethods[key] ?: return true
            if (prevMethod.accessFlags != currMethod.accessFlags) return true
            if (prevMethod.exceptionTableSig != currMethod.exceptionTableSig) return true
            if (prevMethod.visibleAnnotationsSig != currMethod.visibleAnnotationsSig) return true
            val prevSig = normalizeMethodInstructions(prev, prevMethod, expandIndy = true)
            val currSig = normalizeMethodInstructions(curr, currMethod, expandIndy = true)
            if (prevSig != currSig) return true
        }

        return false
    }

    fun isInterfaceClass(classBytes: ByteArray): Boolean {
        val parsed = runCatching { parseDetailedClass(classBytes) }.getOrNull() ?: return false
        return (parsed.accessFlags and ACC_INTERFACE) != 0
    }

    fun isRenamableSyntheticOrAnonymousClass(classBytes: ByteArray): Boolean {
        val parsed = runCatching { parseDetailedClass(classBytes) }.getOrNull() ?: return false
        val simpleName = parsed.thisInternalName.substringAfterLast('/')
        if ('$' !in simpleName) return false
        if (simpleName.endsWith("\$DefaultImpls") ||
            simpleName.endsWith("\$Companion") ||
            simpleName.endsWith("\$Creator") ||
            simpleName.endsWith("\$\$serializer")
        ) {
            return false
        }
        return renamableInnerClassRegex.containsMatchIn(simpleName) ||
            (parsed.accessFlags and ACC_SYNTHETIC) != 0
    }

    fun hasClassHierarchyChange(previousBytes: ByteArray, currentBytes: ByteArray): Boolean {
        val prev = runCatching { parseDetailedClass(previousBytes) }.getOrNull() ?: return false
        val curr = runCatching { parseDetailedClass(currentBytes) }.getOrNull() ?: return false
        val hierarchyFlagsMask = ACC_INTERFACE or ACC_ABSTRACT or ACC_ENUM or ACC_ANNOTATION
        return prev.superInternalName != curr.superInternalName ||
            prev.interfaces != curr.interfaces ||
            (prev.accessFlags and hierarchyFlagsMask) != (curr.accessFlags and hierarchyFlagsMask)
    }

    fun detectUnsupportedRedefinitionReason(
        previousBytes: ByteArray?,
        currentBytes: ByteArray,
        baselineDexMembers: AdditiveClassMerger.DexClassMembers? = null
    ): String? {
        val curr = runCatching { parseDetailedClass(currentBytes) }.getOrNull() ?: return null
        val simpleName = curr.thisInternalName.substringAfterLast('/').ifEmpty { "Unknown" }
        val kindMask = ACC_INTERFACE or ACC_ABSTRACT or ACC_ENUM or ACC_ANNOTATION

        if (previousBytes != null) {
            val prev = runCatching { parseDetailedClass(previousBytes) }.getOrNull() ?: return null
            if ((prev.accessFlags and kindMask) != (curr.accessFlags and kindMask)) {
                return "CLASS_MODIFIERS_CHANGED:$simpleName"
            }
            if (prev.superInternalName != curr.superInternalName) {
                return "SUPERCLASS_CHANGED:$simpleName"
            }
            if (prev.interfaces != curr.interfaces) {
                return "INTERFACE_HIERARCHY_CHANGED:$simpleName"
            }
            return null
        }

        if (baselineDexMembers != null) {
            if ((baselineDexMembers.accessFlags and kindMask) != (curr.accessFlags and kindMask)) {
                return "CLASS_MODIFIERS_CHANGED:$simpleName"
            }
            val dexSuper = baselineDexMembers.superClassName
                ?.replace('.', '/')
                ?.let(::normalizeDesugaredInternalName)
                .orEmpty()
            if (dexSuper.isNotEmpty() && dexSuper != curr.superInternalName) {
                return "SUPERCLASS_CHANGED:$simpleName"
            }
            val dexIfaces = baselineDexMembers.interfaces
                .filterNot { it.contains("\$\$") }
                .map(::normalizeDesugaredInternalName)
            val currIfaces = curr.interfaces.filterNot { it.contains("\$\$") }
            if (dexIfaces != currIfaces) {
                return "INTERFACE_HIERARCHY_CHANGED:$simpleName"
            }
        }
        return null
    }

    private fun normalizeDesugaredInternalName(internalName: String): String {
        return if (internalName.startsWith("j\$/")) {
            "java/" + internalName.removePrefix("j\$/")
        } else {
            internalName
        }
    }

    fun remapSingleInternalClassName(internalName: String, renameMap: Map<String, String>): String {
        if (renameMap.isEmpty()) return internalName
        for ((oldKey, newKey) in renameMap.entries.sortedByDescending { it.key.length }) {
            if (internalName == oldKey) return newKey
            if (internalName.startsWith("$oldKey\$")) {
                return newKey + internalName.substring(oldKey.length)
            }
        }
        return internalName
    }

    fun remapInternalClassNames(classBytes: ByteArray, renameMap: Map<String, String>): ByteArray {
        if (renameMap.isEmpty() || classBytes.size < MIN_CLASS_BYTES) return classBytes
        val buf = ByteBuffer.wrap(classBytes)
        if (buf.int != CLASS_MAGIC) return classBytes
        buf.short // minor
        buf.short // major
        val cpCount = buf.short.toInt() and U16_MASK
        val cpStartOffset = buf.position()

        val sortedEntries = renameMap.entries
            .sortedByDescending { it.key.length }
            .map { (oldInternal, newInternal) ->
                ClassRenameRule(
                    oldInternal = oldInternal,
                    newInternal = newInternal,
                    oldBinary = oldInternal.replace('/', '.'),
                    newBinary = newInternal.replace('/', '.')
                )
            }

        val cpOut = ByteArrayOutputStream(classBytes.size)
        var anyChanged = false
        var slot = 1
        while (slot < cpCount) {
            val entryStart = buf.position()
            if (entryStart >= classBytes.size) return classBytes
            val tag = buf.get().toInt() and 0xFF
            if (tag == CP_TAG_UTF8) {
                if (buf.remaining() < 2) return classBytes
                val len = buf.short.toInt() and U16_MASK
                if (buf.remaining() < len) return classBytes
                val strOffset = buf.position()
                buf.position(strOffset + len)
                val decoded = String(classBytes, strOffset, len, Charsets.UTF_8)
                val rewritten = remapUtf8String(decoded, sortedEntries)
                if (rewritten !== decoded) {
                    val encoded = rewritten.toByteArray(Charsets.UTF_8)
                    if (encoded.size <= U16_MASK) {
                        anyChanged = true
                        cpOut.write(CP_TAG_UTF8)
                        writeU2(cpOut, encoded.size)
                        cpOut.write(encoded)
                        slot += 1
                        continue
                    }
                }
                cpOut.write(classBytes, entryStart, (strOffset + len) - entryStart)
                slot += 1
            } else {
                val skip = when (tag) {
                    CP_TAG_CLASS, CP_TAG_STRING, CP_TAG_METHOD_TYPE, CP_TAG_MODULE, CP_TAG_PACKAGE -> 2
                    CP_TAG_METHOD_HANDLE -> 3
                    CP_TAG_INTEGER, CP_TAG_FLOAT, CP_TAG_FIELDREF, CP_TAG_METHODREF,
                    CP_TAG_INTERFACE_METHODREF, CP_TAG_NAME_AND_TYPE, CP_TAG_DYNAMIC, CP_TAG_INVOKE_DYNAMIC -> 4
                    CP_TAG_LONG, CP_TAG_DOUBLE -> 8
                    else -> return classBytes
                }
                if (buf.remaining() < skip) return classBytes
                buf.position(buf.position() + skip)
                cpOut.write(classBytes, entryStart, 1 + skip)
                slot += if (tag == CP_TAG_LONG || tag == CP_TAG_DOUBLE) 2 else 1
            }
        }
        if (!anyChanged) return classBytes
        val cpEndOffset = buf.position()
        val out = ByteArrayOutputStream(cpStartOffset + cpOut.size() + (classBytes.size - cpEndOffset))
        out.write(classBytes, 0, cpStartOffset)
        cpOut.writeTo(out)
        out.write(classBytes, cpEndOffset, classBytes.size - cpEndOffset)
        return out.toByteArray()
    }

    private class ClassRenameRule(
        val oldInternal: String,
        val newInternal: String,
        val oldBinary: String,
        val newBinary: String
    )

    private fun remapUtf8String(input: String, rules: List<ClassRenameRule>): String {
        for (rule in rules) {
            if (input == rule.oldInternal) return rule.newInternal
            if (input.startsWith("${rule.oldInternal}\$")) {
                return rule.newInternal + input.substring(rule.oldInternal.length)
            }
            if (input == rule.oldBinary) return rule.newBinary
            if (input.startsWith("${rule.oldBinary}\$")) {
                return rule.newBinary + input.substring(rule.oldBinary.length)
            }
        }
        if ('L' !in input) return input
        var sb: StringBuilder? = null
        var cursor = 0
        var idx = 0
        while (idx < input.length) {
            if (input[idx] == 'L') {
                val matchedRule = rules.firstOrNull { rule ->
                    val afterPos = idx + 1 + rule.oldInternal.length
                    afterPos < input.length &&
                        input.regionMatches(idx + 1, rule.oldInternal, 0, rule.oldInternal.length) &&
                        (input[afterPos] == ';' || input[afterPos] == '<' || input[afterPos] == '$')
                }
                if (matchedRule != null) {
                    if (sb == null) sb = StringBuilder(input.length + 16)
                    sb.append(input, cursor, idx + 1)
                    sb.append(matchedRule.newInternal)
                    idx += 1 + matchedRule.oldInternal.length
                    cursor = idx
                    continue
                }
            }
            idx++
        }
        if (sb == null) return input
        sb.append(input, cursor, input.length)
        return sb.toString()
    }

    fun extractInterfaceDefaultMethods(interfaceBytes: ByteArray): List<Pair<String, String>> {
        val parsed = runCatching { parseDetailedClass(interfaceBytes) }.getOrNull() ?: return emptyList()
        if ((parsed.accessFlags and ACC_INTERFACE) == 0) return emptyList()
        return parsed.methods
            .filter { m ->
                (m.accessFlags and ACC_ABSTRACT) == 0 &&
                    (m.accessFlags and ACC_STATIC) == 0 &&
                    (m.accessFlags and ACC_PRIVATE) == 0 &&
                    !m.name.endsWith("\$jd") &&
                    m.name != "<init>" &&
                    m.name != "<clinit>" &&
                    m.codeAttrStartOffset >= 0
            }
            .map { it.name to it.descriptor }
    }

    fun hasInterfaceStructureChange(previousBytes: ByteArray?, currentBytes: ByteArray): Boolean {
        if (previousBytes == null) return false
        val prev = runCatching { parseDetailedClass(previousBytes) }.getOrNull() ?: return true
        val curr = runCatching { parseDetailedClass(currentBytes) }.getOrNull() ?: return true

        if (prev.accessFlags != curr.accessFlags || prev.interfaces != curr.interfaces) {
            return true
        }
        val prevFields = prev.fields.map { Triple(it.accessFlags, it.name, it.descriptor) }
        val currFields = curr.fields.map { Triple(it.accessFlags, it.name, it.descriptor) }
        if (prevFields != currFields) return true

        val prevMethods = prev.methods
            .filter { !it.name.endsWith("\$jd") }
            .map { Triple(it.accessFlags, it.name, it.descriptor) }
        val currMethods = curr.methods
            .filter { !it.name.endsWith("\$jd") }
            .map { Triple(it.accessFlags, it.name, it.descriptor) }
        return prevMethods != currMethods
    }

    fun synthesizeDefaultImplsFromInterface(interfaceBytes: ByteArray): ByteArray? {
        val parsed = runCatching { parseDetailedClass(interfaceBytes) }.getOrNull() ?: return null
        if ((parsed.accessFlags and ACC_INTERFACE) == 0 || parsed.thisInternalName.isEmpty()) return null

        val defaultMethods = parsed.methods.filter { m ->
            (m.accessFlags and ACC_ABSTRACT) == 0 &&
                !m.name.endsWith("\$jd") &&
                m.name != "<init>" &&
                m.name != "<clinit>" &&
                m.codeAttrStartOffset >= 0 &&
                m.codeAttrEndOffset > m.codeAttrStartOffset
        }
        if (defaultMethods.isEmpty()) return null

        val privateInstanceMethods = defaultMethods
            .filter { (it.accessFlags and ACC_PRIVATE) != 0 && (it.accessFlags and ACC_STATIC) == 0 }
            .map { it.name to it.descriptor }
            .toSet()

        var nextCpSlot = parsed.cpCount
        val extraCp = ByteArrayOutputStream(256)

        fun appendUtf8(value: String): Int {
            val encoded = value.toByteArray(Charsets.UTF_8)
            val slot = nextCpSlot++
            extraCp.write(CP_TAG_UTF8)
            writeU2(extraCp, encoded.size)
            extraCp.write(encoded)
            return slot
        }

        val defaultImplsInternalName = "${parsed.thisInternalName}\$DefaultImpls"
        val defaultImplsUtf8Slot = appendUtf8(defaultImplsInternalName)
        val defaultImplsClassSlot = nextCpSlot++
        extraCp.write(CP_TAG_CLASS)
        writeU2(extraCp, defaultImplsUtf8Slot)

        val descSlotByMethod = IdentityHashMap<ParsedMethod, Int>()
        for (method in defaultMethods) {
            val newDesc = if ((method.accessFlags and ACC_STATIC) == 0) {
                "(L${parsed.thisInternalName};${method.descriptor.removePrefix("(")}"
            } else {
                method.descriptor
            }
            descSlotByMethod[method] = appendUtf8(newDesc)
        }

        val patchedBootstrapAttr = patchBootstrapAttrForDefaultImpls(
            interfaceBytes = interfaceBytes,
            parsed = parsed,
            defaultMethods = defaultMethods,
            defaultImplsClassSlot = defaultImplsClassSlot,
            descSlotByMethod = descSlotByMethod,
            extraCp = extraCp,
            allocateCpSlot = { nextCpSlot++ }
        )

        val out = ByteArrayOutputStream(interfaceBytes.size + extraCp.size() + 64)
        out.write(interfaceBytes, 0, 8)
        writeU2(out, nextCpSlot)
        out.write(interfaceBytes, parsed.cpStartOffset, parsed.cpEndOffset - parsed.cpStartOffset)
        extraCp.writeTo(out)

        writeU2(out, ACC_PUBLIC or ACC_FINAL or ACC_SUPER)
        writeU2(out, defaultImplsClassSlot)
        writeU2(out, parsed.superClassCpIdx)
        writeU2(out, 0) // interfaces_count
        writeU2(out, 0) // fields_count
        writeU2(out, defaultMethods.size) // methods_count

        for (method in defaultMethods) {
            val newFlags = ACC_PUBLIC or ACC_STATIC or (method.accessFlags and ACC_SYNTHETIC)
            writeU2(out, newFlags)
            writeU2(out, method.nameCpIdx)
            writeU2(out, descSlotByMethod.getValue(method))
            writeU2(out, 1) // attributes_count = 1 (Code attribute only)
            out.write(
                interfaceBytes,
                method.codeAttrStartOffset,
                method.codeAttrEndOffset - method.codeAttrStartOffset
            )
        }

        if (patchedBootstrapAttr != null) {
            writeU2(out, 1)
            out.write(patchedBootstrapAttr)
        } else {
            writeU2(out, 0)
        }

        return rewriteInterfaceSpecialCallsToDefaultImpls(
            classBytes = out.toByteArray(),
            interfaceInternalNames = setOf(parsed.thisInternalName),
            privateInstanceMethodsByIface = mapOf(parsed.thisInternalName to privateInstanceMethods)
        )
    }

    private fun patchBootstrapAttrForDefaultImpls(
        interfaceBytes: ByteArray,
        parsed: DetailedClass,
        defaultMethods: List<ParsedMethod>,
        defaultImplsClassSlot: Int,
        descSlotByMethod: Map<ParsedMethod, Int>,
        extraCp: ByteArrayOutputStream,
        allocateCpSlot: () -> Int
    ): ByteArray? {
        val start = parsed.bootstrapAttrStartOffset
        val end = parsed.bootstrapAttrEndOffset
        if (start < 0 || end <= start + 8) return null

        val rawAttr = interfaceBytes.copyOfRange(start, end)
        val movedBySig = defaultMethods.associateBy { it.name to it.descriptor }
        val remappedMhSlots = HashMap<Int, Int>()

        fun remapMethodHandleIfNeeded(mhCpIdx: Int): Int? {
            remappedMhSlots[mhCpIdx]?.let { return it }
            val mh = parsed.methodHandleByCp[mhCpIdx] ?: return null
            val ref = parsed.resolveMemberRef(mh.second) ?: return null
            if (ref.first != parsed.thisInternalName) return null
            val moved = movedBySig[ref.second to ref.third] ?: return null
            val isMovedStaticOrPrivate = (moved.accessFlags and ACC_STATIC) != 0 ||
                (moved.accessFlags and ACC_PRIVATE) != 0
            if (!isMovedStaticOrPrivate) return null

            val natSlot = allocateCpSlot()
            extraCp.write(CP_TAG_NAME_AND_TYPE)
            writeU2(extraCp, moved.nameCpIdx)
            writeU2(extraCp, descSlotByMethod.getValue(moved))

            val mrefSlot = allocateCpSlot()
            extraCp.write(CP_TAG_METHODREF)
            writeU2(extraCp, defaultImplsClassSlot)
            writeU2(extraCp, natSlot)

            val newMhSlot = allocateCpSlot()
            extraCp.write(CP_TAG_METHOD_HANDLE)
            extraCp.write(MH_REF_INVOKE_STATIC)
            writeU2(extraCp, mrefSlot)

            remappedMhSlots[mhCpIdx] = newMhSlot
            return newMhSlot
        }

        val numBsm = ((rawAttr[6].toInt() and 0xFF) shl 8) or (rawAttr[7].toInt() and 0xFF)
        var pos = 8
        repeat(numBsm) {
            if (pos + 4 > rawAttr.size) return rawAttr
            val numArgs = ((rawAttr[pos + 2].toInt() and 0xFF) shl 8) or (rawAttr[pos + 3].toInt() and 0xFF)
            pos += 4
            repeat(numArgs) {
                if (pos + 2 > rawAttr.size) return rawAttr
                val argCpIdx = ((rawAttr[pos].toInt() and 0xFF) shl 8) or (rawAttr[pos + 1].toInt() and 0xFF)
                val newSlot = remapMethodHandleIfNeeded(argCpIdx)
                if (newSlot != null) {
                    rawAttr[pos] = ((newSlot ushr 8) and 0xFF).toByte()
                    rawAttr[pos + 1] = (newSlot and 0xFF).toByte()
                }
                pos += 2
            }
        }
        return rawAttr
    }

    private data class DefaultImplsPatchSite(
        val absPc: Int,
        val clearTrailingTwoBytes: Boolean,
        val target: Triple<String, String, String>
    )

    fun rewriteInterfaceSpecialCallsToDefaultImpls(
        classBytes: ByteArray,
        interfaceInternalNames: Set<String>,
        privateInstanceMethodsByIface: Map<String, Set<Pair<String, String>>> = emptyMap()
    ): ByteArray {
        if (interfaceInternalNames.isEmpty()) return classBytes
        val parsed = runCatching { parseDetailedClass(classBytes) }.getOrNull() ?: return classBytes
        if ((parsed.accessFlags and ACC_INTERFACE) != 0) return classBytes

        val patchSites = collectDefaultImplsPatchSites(
            parsed = parsed,
            interfaceInternalNames = interfaceInternalNames,
            privateInstanceMethodsByIface = privateInstanceMethodsByIface
        )
        if (patchSites.isEmpty()) return classBytes

        var nextCpSlot = parsed.cpCount
        val extraCp = ByteArrayOutputStream(128)

        fun appendUtf8(value: String): Int {
            val encoded = value.toByteArray(Charsets.UTF_8)
            val slot = nextCpSlot++
            extraCp.write(CP_TAG_UTF8)
            writeU2(extraCp, encoded.size)
            extraCp.write(encoded)
            return slot
        }

        val defaultImplsClassSlotByIface = HashMap<String, Int>()
        val methodRefSlotByTarget = HashMap<Triple<String, String, String>, Int>()

        for (site in patchSites) {
            val target = site.target
            if (target in methodRefSlotByTarget) continue
            val (ifaceOwner, methodName, staticDesc) = target
            val ownerClassSlot = defaultImplsClassSlotByIface.getOrPut(ifaceOwner) {
                val utf8Slot = appendUtf8("$ifaceOwner\$DefaultImpls")
                val clsSlot = nextCpSlot++
                extraCp.write(CP_TAG_CLASS)
                writeU2(extraCp, utf8Slot)
                clsSlot
            }
            val nameUtf8Slot = appendUtf8(methodName)
            val descUtf8Slot = appendUtf8(staticDesc)
            val natSlot = nextCpSlot++
            extraCp.write(CP_TAG_NAME_AND_TYPE)
            writeU2(extraCp, nameUtf8Slot)
            writeU2(extraCp, descUtf8Slot)
            val mrefSlot = nextCpSlot++
            extraCp.write(CP_TAG_METHODREF)
            writeU2(extraCp, ownerClassSlot)
            writeU2(extraCp, natSlot)
            methodRefSlotByTarget[target] = mrefSlot
        }

        val patched = classBytes.clone()
        for (site in patchSites) {
            val mrefSlot = methodRefSlotByTarget.getValue(site.target)
            patched[site.absPc] = 0xB8.toByte() // invokestatic
            patched[site.absPc + 1] = ((mrefSlot ushr 8) and 0xFF).toByte()
            patched[site.absPc + 2] = (mrefSlot and 0xFF).toByte()
            if (site.clearTrailingTwoBytes) {
                patched[site.absPc + 3] = 0x00 // nop
                patched[site.absPc + 4] = 0x00 // nop
            }
        }

        val out = ByteArrayOutputStream(patched.size + extraCp.size() + 8)
        out.write(patched, 0, 8)
        writeU2(out, nextCpSlot)
        out.write(patched, parsed.cpStartOffset, parsed.cpEndOffset - parsed.cpStartOffset)
        extraCp.writeTo(out)
        out.write(patched, parsed.cpEndOffset, patched.size - parsed.cpEndOffset)
        return out.toByteArray()
    }

    private fun collectDefaultImplsPatchSites(
        parsed: DetailedClass,
        interfaceInternalNames: Set<String>,
        privateInstanceMethodsByIface: Map<String, Set<Pair<String, String>>>
    ): List<DefaultImplsPatchSite> {
        val patchSites = mutableListOf<DefaultImplsPatchSite>()
        for (method in parsed.methods) {
            val code = method.codeBytes ?: continue
            if (method.codeOffsetInClass < 0) continue
            for (insn in walkBytecode(parsed, code)) {
                val site = resolveDefaultImplsPatchSite(
                    parsed = parsed,
                    method = method,
                    insn = insn,
                    interfaceInternalNames = interfaceInternalNames,
                    privateInstanceMethodsByIface = privateInstanceMethodsByIface
                )
                if (site != null) {
                    patchSites.add(site)
                }
            }
        }
        return patchSites
    }

    private fun resolveDefaultImplsPatchSite(
        parsed: DetailedClass,
        method: ParsedMethod,
        insn: BytecodeInsn,
        interfaceInternalNames: Set<String>,
        privateInstanceMethodsByIface: Map<String, Set<Pair<String, String>>>
    ): DefaultImplsPatchSite? {
        if (insn.opcode !in 0xB7..0xB9) return null
        val ref = parsed.resolveMemberRef(insn.operand) ?: return null
        val (owner, name, desc) = ref
        if (owner !in interfaceInternalNames || name == "<init>" || name == "<clinit>") return null

        val absPc = method.codeOffsetInClass + insn.pc
        return when (insn.opcode) {
            0xB7 -> { // invokespecial -> static (L$owner;...)
                val staticDesc = "(L$owner;${desc.removePrefix("(")}"
                DefaultImplsPatchSite(absPc, false, Triple(owner, name, staticDesc))
            }
            0xB8 -> { // invokestatic (e.g. $default bridge or static helper on interface)
                if (name.endsWith("\$jd")) {
                    null
                } else {
                    DefaultImplsPatchSite(absPc, false, Triple(owner, name, desc))
                }
            }
            0xB9 -> { // invokeinterface -> only rewrite private instance interface methods
                val isPrivateInstance = (name to desc) in privateInstanceMethodsByIface[owner].orEmpty()
                if (!isPrivateInstance) {
                    null
                } else {
                    val staticDesc = "(L$owner;${desc.removePrefix("(")}"
                    DefaultImplsPatchSite(absPc, true, Triple(owner, name, staticDesc))
                }
            }
            else -> null
        }
    }

    fun injectMissingInterfaceDefaultBridges(
        classBytes: ByteArray,
        defaultMethodsByIface: Map<String, List<Pair<String, String>>>,
        classBytesLookup: (String) -> ByteArray? = { null }
    ): ByteArray {
        if (defaultMethodsByIface.isEmpty()) return classBytes
        val parsed = runCatching { parseDetailedClass(classBytes) }.getOrNull() ?: return classBytes
        if ((parsed.accessFlags and (ACC_INTERFACE or ACC_ABSTRACT)) != 0) return classBytes
        if (parsed.methodsCountOffset < 0 || parsed.methodsEndOffset < parsed.methodsCountOffset + 2) {
            return classBytes
        }

        val missingBridges = resolveMissingInterfaceBridges(
            parsed = parsed,
            defaultMethodsByIface = defaultMethodsByIface,
            classBytesLookup = classBytesLookup
        )
        if (missingBridges.isEmpty()) return classBytes

        var nextCpSlot = parsed.cpCount
        val extraCp = ByteArrayOutputStream(128)
        val utf8ToSlot = parsed.utf8Pool.entries.associate { (slot, text) -> text to slot }.toMutableMap()

        fun getOrAddUtf8(value: String): Int {
            utf8ToSlot[value]?.let { return it }
            val encoded = value.toByteArray(Charsets.UTF_8)
            val slot = nextCpSlot++
            extraCp.write(CP_TAG_UTF8)
            writeU2(extraCp, encoded.size)
            extraCp.write(encoded)
            utf8ToSlot[value] = slot
            return slot
        }

        val codeAttrNameSlot = getOrAddUtf8("Code")
        val ownerClassSlotByIface = HashMap<String, Int>()
        val extraMethods = ByteArrayOutputStream(128)

        for ((ifaceOwner, methodName, methodDesc) in missingBridges) {
            val ownerClassSlot = ownerClassSlotByIface.getOrPut(ifaceOwner) {
                val utf8Slot = getOrAddUtf8("$ifaceOwner\$DefaultImpls")
                val clsSlot = nextCpSlot++
                extraCp.write(CP_TAG_CLASS)
                writeU2(extraCp, utf8Slot)
                clsSlot
            }
            val nameSlot = getOrAddUtf8(methodName)
            val descSlot = getOrAddUtf8(methodDesc)
            val staticDescSlot = getOrAddUtf8("(L$ifaceOwner;${methodDesc.removePrefix("(")}")
            val natSlot = nextCpSlot++
            extraCp.write(CP_TAG_NAME_AND_TYPE)
            writeU2(extraCp, nameSlot)
            writeU2(extraCp, staticDescSlot)
            val mrefSlot = nextCpSlot++
            extraCp.write(CP_TAG_METHODREF)
            writeU2(extraCp, ownerClassSlot)
            writeU2(extraCp, natSlot)

            writeU2(extraMethods, ACC_PUBLIC)
            writeU2(extraMethods, nameSlot)
            writeU2(extraMethods, descSlot)
            writeU2(extraMethods, 1) // Code attribute
            writeForwardingBridgeCodeAttribute(extraMethods, codeAttrNameSlot, methodDesc, mrefSlot)
        }

        val out = ByteArrayOutputStream(classBytes.size + extraCp.size() + extraMethods.size() + 16)
        out.write(classBytes, 0, 8)
        writeU2(out, nextCpSlot)
        out.write(classBytes, parsed.cpStartOffset, parsed.cpEndOffset - parsed.cpStartOffset)
        extraCp.writeTo(out)
        out.write(classBytes, parsed.cpEndOffset, parsed.methodsCountOffset - parsed.cpEndOffset)
        writeU2(out, parsed.methods.size + missingBridges.size)
        val existingMethodsStart = parsed.methodsCountOffset + 2
        out.write(classBytes, existingMethodsStart, parsed.methodsEndOffset - existingMethodsStart)
        extraMethods.writeTo(out)
        out.write(classBytes, parsed.methodsEndOffset, classBytes.size - parsed.methodsEndOffset)
        return out.toByteArray()
    }

    private fun resolveMissingInterfaceBridges(
        parsed: DetailedClass,
        defaultMethodsByIface: Map<String, List<Pair<String, String>>>,
        classBytesLookup: (String) -> ByteArray?
    ): List<Triple<String, String, String>> {
        val implementedIfaces = LinkedHashSet<String>()
        val declaredMethods = HashSet<Pair<String, String>>()
        val ifaceQueue = ArrayDeque<String>()

        fun enqueueInterfaces(ifaces: List<String>) {
            for (iface in ifaces) {
                if (implementedIfaces.add(iface)) {
                    ifaceQueue.addLast(iface)
                }
            }
        }

        parsed.methods.forEach { declaredMethods.add(it.name to it.descriptor) }
        enqueueInterfaces(parsed.interfaces)

        var superName = parsed.superInternalName
        var depth = 0
        while (superName.isNotEmpty() && superName != "java/lang/Object" && depth++ < 16) {
            val superBytes = classBytesLookup(superName) ?: return emptyList()
            val superParsed = runCatching { parseDetailedClass(superBytes) }.getOrNull() ?: return emptyList()
            superParsed.methods.forEach { declaredMethods.add(it.name to it.descriptor) }
            enqueueInterfaces(superParsed.interfaces)
            superName = superParsed.superInternalName
        }

        while (ifaceQueue.isNotEmpty()) {
            val iface = ifaceQueue.removeFirst()
            val ifaceBytes = classBytesLookup(iface) ?: continue
            val ifaceParsed = runCatching { parseDetailedClass(ifaceBytes) }.getOrNull() ?: continue
            enqueueInterfaces(ifaceParsed.interfaces)
        }

        val missing = mutableListOf<Triple<String, String, String>>()
        for (iface in implementedIfaces) {
            val defaults = defaultMethodsByIface[iface] ?: continue
            for ((mName, mDesc) in defaults) {
                if (declaredMethods.add(mName to mDesc)) {
                    missing.add(Triple(iface, mName, mDesc))
                }
            }
        }
        return missing
    }

    private fun writeForwardingBridgeCodeAttribute(
        out: ByteArrayOutputStream,
        codeAttrNameSlot: Int,
        methodDesc: String,
        targetMethodRefSlot: Int
    ) {
        val code = ByteArrayOutputStream(32)
        code.write(0x2A) // aload_0 (this)
        var localSlot = 1
        var i = methodDesc.indexOf('(') + 1
        val endParams = methodDesc.indexOf(')')
        while (i in 1..<endParams) {
            val ch = methodDesc[i]
            val loadOp: Int
            val width: Int
            when (ch) {
                'J' -> {
                    loadOp = 0x16 // lload
                    width = 2
                    i++
                }
                'D' -> {
                    loadOp = 0x18 // dload
                    width = 2
                    i++
                }
                'F' -> {
                    loadOp = 0x17 // fload
                    width = 1
                    i++
                }
                'L' -> {
                    loadOp = 0x19 // aload
                    width = 1
                    val semi = methodDesc.indexOf(';', i)
                    i = if (semi > i) semi + 1 else endParams
                }
                '[' -> {
                    loadOp = 0x19 // aload
                    width = 1
                    while (i < endParams && methodDesc[i] == '[') i++
                    if (i < endParams && methodDesc[i] == 'L') {
                        val semi = methodDesc.indexOf(';', i)
                        i = if (semi > i) semi + 1 else endParams
                    } else {
                        i++
                    }
                }
                else -> {
                    loadOp = 0x15 // iload
                    width = 1
                    i++
                }
            }
            if (localSlot <= 0xFF) {
                code.write(loadOp)
                code.write(localSlot)
            } else {
                code.write(0xC4) // wide
                code.write(loadOp)
                writeU2(code, localSlot)
            }
            localSlot += width
        }

        code.write(0xB8) // invokestatic
        writeU2(code, targetMethodRefSlot)

        val retChar = methodDesc.getOrNull(endParams + 1) ?: 'V'
        val (retOp, retSlots) = when (retChar) {
            'V' -> 0xB1 to 0
            'J' -> 0xAD to 2
            'D' -> 0xAF to 2
            'F' -> 0xAE to 1
            'L', '[' -> 0xB0 to 1
            else -> 0xAC to 1
        }
        code.write(retOp)

        val codeBytes = code.toByteArray()
        val maxStack = maxOf(localSlot, retSlots)
        val attrLen = 12 + codeBytes.size
        writeU2(out, codeAttrNameSlot)
        writeU4(out, attrLen)
        writeU2(out, maxStack)
        writeU2(out, localSlot)
        writeU4(out, codeBytes.size)
        out.write(codeBytes)
        writeU2(out, 0) // exception_table_length
        writeU2(out, 0) // attributes_count
    }

    fun analyzeComposeRememberChanges(
        previousBytes: ByteArray?,
        currentBytes: ByteArray
    ): ComposeRememberDiff {
        if (previousBytes == null) return ComposeRememberDiff(emptySet(), false)
        val prev = runCatching { parseDetailedClass(previousBytes) }.getOrNull()
            ?: return ComposeRememberDiff(emptySet(), false)
        val curr = runCatching { parseDetailedClass(currentBytes) }.getOrNull()
            ?: return ComposeRememberDiff(emptySet(), false)

        val prevLambdaKeys = extractComposableLambdaGroupKeys(prev)
        val currLambdaKeys = extractComposableLambdaGroupKeys(curr)

        val prevComposableMethods = prev.methods.filter { isComposableMethod(prev, it) }
        val currComposableMethods = curr.methods.filter { isComposableMethod(curr, it) }

        val prevByKey = prevComposableMethods.associateBy { it.name to it.descriptor }
        val prevByName = prevComposableMethods.groupBy { it.name }

        val resetKeys = LinkedHashSet<Int>()
        var resetAll = false

        for (currMethod in currComposableMethods) {
            val prevMethod = prevByKey[currMethod.name to currMethod.descriptor]
                ?: prevByName[currMethod.name]?.singleOrNull()
                ?: continue

            val prevRemSig = extractRememberSignature(prev, prevMethod)
            val currRemSig = extractRememberSignature(curr, currMethod)
            if (prevRemSig != currRemSig) {
                val currKey = extractMethodRestartGroupKey(curr, currMethod) ?: currLambdaKeys[currMethod.name]
                val prevKey = extractMethodRestartGroupKey(prev, prevMethod) ?: prevLambdaKeys[prevMethod.name]
                if (currKey != null) resetKeys.add(currKey)
                if (prevKey != null) resetKeys.add(prevKey)
                if (currKey == null && prevKey == null) {
                    resetAll = true
                }
            }
        }

        val (changedScopeKeys, invalidateAllScopes) = computeChangedComposableScopes(
            prev = prev,
            curr = curr,
            prevLambdaKeys = prevLambdaKeys,
            currLambdaKeys = currLambdaKeys
        )

        return ComposeRememberDiff(
            resetScopeKeys = resetKeys,
            resetAllClassScopes = resetAll,
            changedScopeKeys = changedScopeKeys,
            invalidateAllClassScopes = invalidateAllScopes
        )
    }

    private fun computeChangedComposableScopes(
        prev: DetailedClass,
        curr: DetailedClass,
        prevLambdaKeys: Map<String, Int>,
        currLambdaKeys: Map<String, Int>
    ): Pair<Set<Int>, Boolean> {
        val changedKeys = LinkedHashSet<Int>()
        var invalidateAll = false
        val currBootstrapTargets = curr.bootstrapTargets.values.toSet()
        val prevBootstrapTargets = prev.bootstrapTargets.values.toSet()
        val prevAllByKey = prev.methods.associateBy { it.name to it.descriptor }
        val currAllKeys = curr.methods.map { it.name to it.descriptor }.toSet()

        val isSingletonsClass = curr.thisInternalName.contains("ComposableSingletons\$")
        for (currMethod in curr.methods) {
            if (currMethod.name == "<clinit>" || currMethod.name == "<init>") continue
            if (isSingletonsClass && currMethod.name.startsWith("getLambda")) continue
            val sigKey = currMethod.name to currMethod.descriptor
            val isComposable = isComposableMethod(curr, currMethod)
            val isPrivateNonComposableHelper = !isComposable && (currMethod.accessFlags and ACC_PRIVATE) != 0
            if (isPrivateNonComposableHelper || (!isComposable && sigKey in currBootstrapTargets)) continue

            val prevMethod = prevAllByKey[sigKey]
            val changed = prevMethod == null ||
                normalizeMethodInstructions(prev, prevMethod, expandIndy = true) !=
                normalizeMethodInstructions(curr, currMethod, expandIndy = true)
            if (!changed) continue

            if (isComposable) {
                val currKey = extractMethodRestartGroupKey(curr, currMethod) ?: currLambdaKeys[currMethod.name]
                val prevKey = prevMethod?.let {
                    extractMethodRestartGroupKey(prev, it) ?: prevLambdaKeys[it.name]
                }
                if (currKey != null) changedKeys.add(currKey)
                if (prevKey != null) changedKeys.add(prevKey)
                if (currKey == null && prevKey == null) {
                    invalidateAll = true
                }
            } else {
                invalidateAll = true
            }
        }

        for (prevMethod in prev.methods) {
            if (prevMethod.name == "<clinit>" || prevMethod.name == "<init>") continue
            if (isSingletonsClass && prevMethod.name.startsWith("getLambda")) continue
            val sigKey = prevMethod.name to prevMethod.descriptor
            if (sigKey in currAllKeys) continue
            if (isComposableMethod(prev, prevMethod)) {
                val prevKey = extractMethodRestartGroupKey(prev, prevMethod) ?: prevLambdaKeys[prevMethod.name]
                if (prevKey != null) {
                    changedKeys.add(prevKey)
                } else if (sigKey !in prevBootstrapTargets) {
                    invalidateAll = true
                }
            } else if ((prevMethod.accessFlags and ACC_PRIVATE) == 0 && sigKey !in prevBootstrapTargets) {
                invalidateAll = true
            }
        }

        return changedKeys to invalidateAll
    }

    fun prepareStaticFieldUpdates(
        previousBytes: ByteArray?,
        currentBytes: ByteArray
    ): StaticFieldDiffResult {
        if (previousBytes == null) {
            return StaticFieldDiffResult(currentBytes, reinitClinit = false, constantFieldUpdates = emptyList())
        }
        val prev = runCatching { parseDetailedClass(previousBytes) }.getOrNull()
            ?: return StaticFieldDiffResult(currentBytes, reinitClinit = false, constantFieldUpdates = emptyList())
        val curr = runCatching { parseDetailedClass(currentBytes) }.getOrNull()
            ?: return StaticFieldDiffResult(currentBytes, reinitClinit = false, constantFieldUpdates = emptyList())

        val className = curr.thisInternalName.replace('/', '.')
        val prevStaticFields = prev.fields
            .filter { (it.accessFlags and ACC_STATIC) != 0 }
            .associateBy { it.name to it.descriptor }

        val constantUpdates = mutableListOf<StaticFieldUpdate>()
        for (field in curr.fields) {
            if ((field.accessFlags and ACC_STATIC) == 0) continue
            val constVal = field.constantValue ?: continue
            val prevField = prevStaticFields[field.name to field.descriptor]
            if (prevField == null || prevField.constantValue != constVal) {
                constantUpdates.add(
                    StaticFieldUpdate(
                        className = className,
                        fieldName = field.name,
                        descriptor = field.descriptor,
                        value = constVal
                    )
                )
            }
        }

        val currClinit = curr.methods.firstOrNull { it.name == "<clinit>" && it.descriptor == "()V" }
            ?: return StaticFieldDiffResult(
                patchedClassBytes = currentBytes,
                reinitClinit = false,
                constantFieldUpdates = constantUpdates
            )
        val currCode = currClinit.codeBytes
            ?: return StaticFieldDiffResult(
                patchedClassBytes = currentBytes,
                reinitClinit = false,
                constantFieldUpdates = constantUpdates
            )

        val prevClinit = prev.methods.firstOrNull { it.name == "<clinit>" && it.descriptor == "()V" }
        if (prevClinit?.codeBytes == null) {
            return StaticFieldDiffResult(
                patchedClassBytes = currentBytes,
                reinitClinit = true,
                constantFieldUpdates = constantUpdates
            )
        }

        val prevSegments = segmentClinit(prev, prevClinit)
        val currSegments = segmentClinit(curr, currClinit)
        if (currSegments.isEmpty()) {
            val prevClinitSig = normalizeMethodInstructions(prev, prevClinit)
            val currClinitSig = normalizeMethodInstructions(curr, currClinit)
            return StaticFieldDiffResult(
                patchedClassBytes = currentBytes,
                reinitClinit = prevClinitSig != currClinitSig,
                constantFieldUpdates = constantUpdates
            )
        }

        val prevSegByField = prevSegments.associateBy { it.fieldName to it.fieldDesc }
        val changedSegments = mutableListOf<ClinitSegment>()
        val unchangedSegments = mutableListOf<ClinitSegment>()

        val isComposableSingletons = curr.thisInternalName.contains("ComposableSingletons\$")
        for (seg in currSegments) {
            val isSingletonInstance = (seg.fieldName == "INSTANCE" || seg.fieldName == "Companion") &&
                prevSegByField.containsKey(seg.fieldName to seg.fieldDesc)
            if (isSingletonInstance) {
                unchangedSegments.add(seg)
                continue
            }
            val prevSeg = prevSegByField[seg.fieldName to seg.fieldDesc]
            val isComposableSingletonLambda = isComposableSingletons && seg.fieldName.startsWith("lambda")
            if (prevSeg == null || prevSeg.signature != seg.signature || isComposableSingletonLambda) {
                changedSegments.add(seg)
            } else {
                unchangedSegments.add(seg)
            }
        }

        if (changedSegments.isEmpty()) {
            return StaticFieldDiffResult(
                patchedClassBytes = currentBytes,
                reinitClinit = false,
                constantFieldUpdates = constantUpdates
            )
        }

        if (unchangedSegments.isEmpty()) {
            return StaticFieldDiffResult(
                patchedClassBytes = currentBytes,
                reinitClinit = true,
                constantFieldUpdates = constantUpdates
            )
        }

        val patchedBytes = patchClinitUnchangedSegments(
            classBytes = currentBytes,
            clinit = currClinit,
            currCode = currCode,
            unchangedSegments = unchangedSegments,
            changedSegments = changedSegments
        )
        return StaticFieldDiffResult(
            patchedClassBytes = patchedBytes,
            reinitClinit = true,
            constantFieldUpdates = constantUpdates
        )
    }

    private data class ClinitSegment(
        val fieldName: String,
        val fieldDesc: String,
        val startPc: Int,
        val endPc: Int,
        val putstaticPc: Int,
        val hasInternalBranches: Boolean,
        val storedLocals: Set<Int>,
        val loadedLocals: Set<Int>,
        val signature: String
    )

    private fun segmentClinit(cls: DetailedClass, clinit: ParsedMethod): List<ClinitSegment> {
        val code = clinit.codeBytes ?: return emptyList()
        val insns = walkBytecode(cls, code)
        if (insns.isEmpty()) return emptyList()

        val methodsByKey = cls.methods.associateBy { it.name to it.descriptor }
        val segments = mutableListOf<ClinitSegment>()
        var segStartIdx = 0
        var maxBranchTargetPc = -1

        for ((idx, insn) in insns.withIndex()) {
            val branchTarget = getMaxBranchTarget(code, insn)
            if (branchTarget > maxBranchTargetPc) {
                maxBranchTargetPc = branchTarget
            }
            if (insn.opcode == 0xB3) { // putstatic
                val ref = cls.resolveMemberRef(insn.operand)
                val inExceptionRange = clinit.exceptionRanges.any { insn.pc in it }
                val isTopLevelOwnStatic = ref != null &&
                    ref.first == cls.thisInternalName &&
                    insn.pc >= maxBranchTargetPc &&
                    !inExceptionRange
                if (isTopLevelOwnStatic) {
                    val segInsns = insns.subList(segStartIdx, idx + 1)
                    val startPc = segInsns.first().pc
                    val endPc = insn.pc + insn.length
                    val hasBranches = segInsns.any { getMaxBranchTarget(code, it) >= 0 }
                    val stored = mutableSetOf<Int>()
                    val loaded = mutableSetOf<Int>()
                    val sigBuilder = StringBuilder()

                    for (sInsn in segInsns) {
                        extractLocalStoreIndex(sInsn)?.let { stored.add(it) }
                        extractLocalLoadIndex(sInsn)?.let { loaded.add(it) }
                        sigBuilder.append(formatInsnSignature(cls, sInsn, methodsByKey)).append(';')
                    }

                    segments.add(
                        ClinitSegment(
                            fieldName = ref.second,
                            fieldDesc = ref.third,
                            startPc = startPc,
                            endPc = endPc,
                            putstaticPc = insn.pc,
                            hasInternalBranches = hasBranches,
                            storedLocals = stored,
                            loadedLocals = loaded,
                            signature = sigBuilder.toString()
                        )
                    )
                    segStartIdx = idx + 1
                }
            }
        }
        return segments
    }

    private fun patchClinitUnchangedSegments(
        classBytes: ByteArray,
        clinit: ParsedMethod,
        currCode: ByteArray,
        unchangedSegments: List<ClinitSegment>,
        changedSegments: List<ClinitSegment>
    ): ByteArray {
        if (clinit.codeOffsetInClass <= 0) return classBytes
        val cloned = classBytes.clone()
        val base = clinit.codeOffsetInClass

        for (seg in unchangedSegments) {
            val laterChangedLoadsSharedLocal = changedSegments
                .filter { it.startPc > seg.startPc }
                .any { later -> seg.storedLocals.any { it in later.loadedLocals } }

            if (!laterChangedLoadsSharedLocal && !seg.hasInternalBranches && !clinit.hasStackMapTable) {
                for (pc in seg.startPc..<seg.endPc) {
                    cloned[base + pc] = 0x00 // nop
                }
            } else if (seg.fieldName == "INSTANCE" &&
                laterChangedLoadsSharedLocal &&
                seg.storedLocals == setOf(0) &&
                seg.endPc - seg.startPc >= 4 &&
                !seg.hasInternalBranches
            ) {
                // Replace `new C; dup; invokespecial <init>; astore_0; aload_0; putstatic INSTANCE`
                // with `getstatic INSTANCE; astore_0; nop...`
                val fieldRefHi = currCode[seg.putstaticPc + 1]
                val fieldRefLo = currCode[seg.putstaticPc + 2]
                cloned[base + seg.startPc] = 0xB2.toByte() // getstatic
                cloned[base + seg.startPc + 1] = fieldRefHi
                cloned[base + seg.startPc + 2] = fieldRefLo
                cloned[base + seg.startPc + 3] = 0x4B.toByte() // astore_0
                for (pc in (seg.startPc + 4)..<seg.endPc) {
                    cloned[base + pc] = 0x00 // nop
                }
            } else {
                // Replace `putstatic` (3 bytes) with `pop`/`pop2` + `nop` + `nop`
                val popOpcode = if (seg.fieldDesc == "J" || seg.fieldDesc == "D") 0x58 else 0x57
                cloned[base + seg.putstaticPc] = popOpcode.toByte()
                cloned[base + seg.putstaticPc + 1] = 0x00
                cloned[base + seg.putstaticPc + 2] = 0x00
            }
        }
        return cloned
    }

    private fun isComposableMethod(cls: DetailedClass, method: ParsedMethod): Boolean {
        if (method.descriptor.contains(COMPOSER_DESC)) return true
        if (method.name == "invoke" && cls.interfaces.any { it.startsWith("kotlin/jvm/functions/Function") }) {
            val code = method.codeBytes ?: return false
            val insns = walkBytecode(cls, code)
            return insns.any { insn ->
                val ref = cls.resolveMemberRef(insn.operand)
                ref != null && (ref.first == COMPOSER_OWNER || ref.third.contains(COMPOSER_DESC))
            }
        }
        return false
    }

    private fun extractMethodRestartGroupKey(cls: DetailedClass, method: ParsedMethod): Int? {
        val code = method.codeBytes ?: return null
        val insns = walkBytecode(cls, code)
        for ((idx, insn) in insns.withIndex()) {
            if (insn.opcode in 0xB6..0xB9) {
                val ref = cls.resolveMemberRef(insn.operand) ?: continue
                if (ref.first == COMPOSER_OWNER && ref.second == "startRestartGroup" && idx > 0) {
                    for (back in (idx - 1) downTo maxOf(0, idx - 4)) {
                        insns[back].intConstant?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun extractComposableLambdaGroupKeys(cls: DetailedClass): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (method in cls.methods) {
            val code = method.codeBytes ?: continue
            val insns = walkBytecode(cls, code)
            for ((idx, insn) in insns.withIndex()) {
                if (insn.opcode == 0xB8) { // invokestatic
                    val ref = cls.resolveMemberRef(insn.operand) ?: continue
                    if (ref.first == COMPOSABLE_LAMBDA_KT && ref.second in composableLambdaFactories) {
                        var targetLambdaName: String? = null
                        var groupKey: Int? = null
                        val windowStart = maxOf(0, idx - 32)
                        for (back in (idx - 1) downTo windowStart) {
                            val bInsn = insns[back]
                            if (targetLambdaName == null && bInsn.opcode == 0xBA) { // invokedynamic
                                val indy = cls.invokeDynamicByCp[bInsn.operand]
                                val target = indy?.let { cls.bootstrapTargets[it.first] }
                                if (target != null) {
                                    targetLambdaName = target.first
                                }
                            } else if (targetLambdaName != null && groupKey == null && bInsn.intConstant != null) {
                                // Skip the boolean `tracked` (0 or 1) immediately before `block`
                                val isTrackedBool = (bInsn.opcode == 0x03 || bInsn.opcode == 0x04) &&
                                    back > windowStart &&
                                    insns[back - 1].intConstant != null
                                if (!isTrackedBool) {
                                    groupKey = bInsn.intConstant
                                    break
                                }
                            }
                        }
                        if (targetLambdaName != null && groupKey != null) {
                            result[targetLambdaName] = groupKey
                        }
                    }
                }
            }
        }
        return result
    }

    private fun extractRememberSignature(cls: DetailedClass, method: ParsedMethod): List<String> {
        val code = method.codeBytes ?: return emptyList()
        val insns = walkBytecode(cls, code)
        if (insns.isEmpty()) return emptyList()

        val methodsByKey = cls.methods.associateBy { it.name to it.descriptor }
        val signature = mutableListOf<String>()
        val pendingChanged = mutableListOf<String>()

        var i = 0
        while (i < insns.size) {
            val insn = insns[i]
            if (insn.opcode in 0xB6..0xB9) {
                val ref = cls.resolveMemberRef(insn.operand)
                if (ref != null) {
                    val (owner, name, desc) = ref
                    if (owner == COMPOSER_OWNER && name.startsWith("changed")) {
                        pendingChanged.add(desc)
                        i++
                        continue
                    }
                    if (owner == COMPOSER_OWNER && name == "rememberedValue" && desc == "()Ljava/lang/Object;") {
                        val endIdx = findMatchingUpdateRememberedValue(cls, insns, i + 1)
                        if (endIdx > i) {
                            val blockInsns = insns.subList(i + 1, endIdx)
                            if (!isCompilerMemoizedLambdaBlock(cls, blockInsns)) {
                                val blockSig = blockInsns.indices.joinToString(",") { bIdx ->
                                    if (isComposeSourceInfoLdc(cls, blockInsns, bIdx)) {
                                        "LDC:COMPOSE_SOURCE_INFO"
                                    } else {
                                        formatRememberBlockInsn(cls, blockInsns[bIdx], methodsByKey)
                                    }
                                }
                                signature.add("BLOCK:${pendingChanged.joinToString("+")}|$blockSig")
                            }
                            pendingChanged.clear()
                            i = endIdx + 1
                            continue
                        }
                    }
                    if (desc.contains(COMPOSER_DESC) && isStatefulComposableCall(name)) {
                        val lambdaSig = findPrecedingLambdaSignature(cls, insns, i, methodsByKey)
                        val flowSourceSig = if (name.startsWith("collectAsState")) {
                            findPrecedingFlowSourceSignature(cls, insns, i)
                        } else {
                            ""
                        }
                        signature.add("CALL:$owner.$name:$desc|$lambdaSig|$flowSourceSig")
                        pendingChanged.clear()
                    } else if (desc.contains(COMPOSER_DESC)) {
                        pendingChanged.clear()
                    }
                }
            }
            i++
        }
        val stateDelegatePrefix = "${method.name}\$lambda\$"
        val delegateReturnSigs = cls.methods.asSequence()
            .filter {
                (it.accessFlags and ACC_SYNTHETIC) != 0 &&
                    it.name.startsWith(stateDelegatePrefix) &&
                    it.descriptor.startsWith(STATE_DESC_PREFIX)
            }
            .map { it.descriptor.removePrefix(STATE_DESC_PREFIX) }
            .toList()
        if (delegateReturnSigs.isNotEmpty() && signature.isNotEmpty()) {
            signature.add("DELEGATES:${delegateReturnSigs.joinToString(",")}")
        }
        return signature
    }

    private fun findMatchingUpdateRememberedValue(
        cls: DetailedClass,
        insns: List<BytecodeInsn>,
        startIdx: Int
    ): Int {
        val limit = minOf(insns.size, startIdx + 80)
        for (idx in startIdx..<limit) {
            val insn = insns[idx]
            if (insn.opcode in 0xB6..0xB9) {
                val ref = cls.resolveMemberRef(insn.operand) ?: continue
                if (ref.first == COMPOSER_OWNER && ref.second == "updateRememberedValue") {
                    return idx
                }
                if (ref.first == COMPOSER_OWNER && ref.second == "rememberedValue") {
                    return -1
                }
            }
        }
        return -1
    }

    private fun isCompilerMemoizedLambdaBlock(cls: DetailedClass, blockInsns: List<BytecodeInsn>): Boolean {
        var hasLambdaAllocation = false
        for (insn in blockInsns) {
            when (insn.opcode) {
                0xBA -> { // invokedynamic
                    val indy = cls.invokeDynamicByCp[insn.operand] ?: return false
                    val nat = cls.nameAndTypeByCp[indy.second] ?: return false
                    val desc = cls.utf8Pool[nat.second].orEmpty()
                    if (desc.contains(")Lkotlin/jvm/functions/Function") ||
                        desc.contains(")Lkotlin/Function")
                    ) {
                        hasLambdaAllocation = true
                    } else {
                        return false
                    }
                }
                in 0xB6..0xB9 -> {
                    val ref = cls.resolveMemberRef(insn.operand) ?: return false
                    val (owner, name, _) = ref
                    if (owner == COMPOSER_KT_OWNER || owner.startsWith("$COMPOSER_OWNER\$")) continue
                    val isSyntheticLambdaInit = name == "<init>" &&
                        (owner.contains("\$") || owner.contains("Lambda"))
                    if (isSyntheticLambdaInit) {
                        hasLambdaAllocation = true
                    } else {
                        return false
                    }
                }
            }
        }
        return hasLambdaAllocation
    }

    private fun isStatefulComposableCall(name: String): Boolean {
        if (name == "rememberedValue" || name == "rememberComposableLambda" || name == "rememberUpdatedState") {
            return false
        }
        return name.startsWith("remember") ||
            name.startsWith("collectAsState") ||
            name.startsWith("animate") ||
            name.startsWith("produceState")
    }

    private fun findPrecedingFlowSourceSignature(
        cls: DetailedClass,
        insns: List<BytecodeInsn>,
        callIdx: Int
    ): String {
        val start = maxOf(0, callIdx - 14)
        for (idx in (callIdx - 1) downTo start) {
            val insn = insns[idx]
            if (insn.opcode in 0xB2..0xB9) {
                val ref = cls.resolveMemberRef(insn.operand) ?: continue
                if (ref.third.contains(FLOW_PKG_MARKER)) {
                    return "${ref.first}.${ref.second}:${ref.third}"
                }
            }
        }
        return ""
    }

    private fun findPrecedingLambdaSignature(
        cls: DetailedClass,
        insns: List<BytecodeInsn>,
        callIdx: Int,
        methodsByKey: Map<Pair<String, String>, ParsedMethod>
    ): String {
        val start = maxOf(0, callIdx - 10)
        for (idx in (callIdx - 1) downTo start) {
            val insn = insns[idx]
            if (insn.opcode == 0xBA) {
                val indy = cls.invokeDynamicByCp[insn.operand] ?: continue
                val target = cls.bootstrapTargets[indy.first] ?: continue
                val targetMethod = methodsByKey[target] ?: continue
                val code = targetMethod.codeBytes ?: return ""
                val targetInsns = walkBytecode(cls, code)
                return targetInsns.indices.joinToString(";") { tIdx ->
                    if (isComposeSourceInfoLdc(cls, targetInsns, tIdx)) {
                        "LDC:COMPOSE_SOURCE_INFO"
                    } else {
                        formatRememberBlockInsn(cls, targetInsns[tIdx], methodsByKey)
                    }
                }
            }
        }
        return ""
    }

    private fun formatRememberBlockInsn(
        cls: DetailedClass,
        insn: BytecodeInsn,
        methodsByKey: Map<Pair<String, String>, ParsedMethod>
    ): String = when (insn.opcode) {
        0x15, in 0x1A..0x1D -> "ILOAD"
        0x16, in 0x1E..0x21 -> "LLOAD"
        0x17, in 0x22..0x25 -> "FLOAD"
        0x18, in 0x26..0x29 -> "DLOAD"
        0x19, in 0x2A..0x2D -> "ALOAD"
        0x36, in 0x3B..0x3E -> "ISTORE"
        0x37, in 0x3F..0x42 -> "LSTORE"
        0x38, in 0x43..0x46 -> "FSTORE"
        0x39, in 0x47..0x4A -> "DSTORE"
        0x3A, in 0x4B..0x4E -> "ASTORE"
        in 0x99..0xA8, 0xC6, 0xC7, 0xC8, 0xC9 -> "BR_${insn.opcode}"
        else -> formatInsnSignature(cls, insn, methodsByKey)
    }

    private fun normalizeMethodInstructions(
        cls: DetailedClass,
        method: ParsedMethod,
        expandIndy: Boolean = false
    ): String {
        val code = method.codeBytes ?: return ""
        val insns = walkBytecode(cls, code)
        val methodsByKey = cls.methods.associateBy { it.name to it.descriptor }
        val callerKey = method.name to method.descriptor
        return insns.indices.joinToString(";") { idx ->
            if (isComposeSourceInfoLdc(cls, insns, idx)) {
                "LDC:COMPOSE_SOURCE_INFO"
            } else {
                formatInsnSignature(
                    cls = cls,
                    insn = insns[idx],
                    methodsByKey = methodsByKey,
                    expandIndy = expandIndy,
                    callerKey = callerKey
                )
            }
        }
    }

    private fun isComposeSourceInfoLdc(
        cls: DetailedClass,
        insns: List<BytecodeInsn>,
        idx: Int
    ): Boolean {
        val insn = insns[idx]
        if (insn.opcode != 0x12 && insn.opcode != 0x13) return false
        val next = insns.getOrNull(idx + 1) ?: return false
        if (next.opcode != 0xB8) return false
        val ref = cls.resolveMemberRef(next.operand) ?: return false
        return ref.first == COMPOSER_KT_OWNER && ref.second in composeSourceInfoMethods
    }

    private fun formatInsnSignature(
        cls: DetailedClass,
        insn: BytecodeInsn,
        methodsByKey: Map<Pair<String, String>, ParsedMethod>,
        expandIndy: Boolean = true,
        callerKey: Pair<String, String>? = null
    ): String {
        if (insn.intConstant != null) {
            return "INT:${insn.intConstant}"
        }
        return when (insn.opcode) {
            0x12, 0x13, 0x14 -> { // ldc, ldc_w, ldc2_w
                val constVal = cls.constValByCp[insn.operand]
                    ?: cls.resolveClassName(insn.operand).takeIf { it.isNotEmpty() }
                    ?: insn.operand.toString()
                "LDC:$constVal"
            }
            in 0xB2..0xB9 -> { // getstatic..invokeinterface
                val ref = cls.resolveMemberRef(insn.operand)
                if (ref != null) {
                    val base = "REF_${insn.opcode}:${ref.first}.${ref.second}:${ref.third}"
                    val isSelfCall = callerKey != null &&
                        ref.second == callerKey.first &&
                        ref.third == callerKey.second
                    if (expandIndy && ref.first == cls.thisInternalName &&
                        ref.second != "<clinit>" && !isSelfCall
                    ) {
                        val targetMethod = methodsByKey[ref.second to ref.third]
                        if (targetMethod != null && !isComposableMethod(cls, targetMethod)) {
                            "$base[${normalizeMethodInstructions(cls, targetMethod, expandIndy = false)}]"
                        } else {
                            base
                        }
                    } else {
                        base
                    }
                } else {
                    "OP_${insn.opcode}:${insn.operand}"
                }
            }
            0xBA -> { // invokedynamic
                val indy = cls.invokeDynamicByCp[insn.operand]
                val nat = indy?.let { cls.nameAndTypeByCp[it.second] }
                val name = nat?.let { cls.utf8Pool[it.first] }.orEmpty()
                val desc = nat?.let { cls.utf8Pool[it.second] }.orEmpty()
                val target = indy?.let { cls.bootstrapTargets[it.first] }
                val targetSig = if (expandIndy && target != null) {
                    methodsByKey[target]?.let { normalizeMethodInstructions(cls, it, expandIndy = false) }.orEmpty()
                } else {
                    target?.toString().orEmpty()
                }
                val bsmArgs = indy?.let { cls.bootstrapArgsSummary[it.first] }.orEmpty()
                "INDY:$name:$desc[$targetSig|$bsmArgs]"
            }
            0xBB, 0xBD, 0xC0, 0xC1 -> { // new, anewarray, checkcast, instanceof
                "TYPE_${insn.opcode}:${cls.resolveClassName(insn.operand)}"
            }
            0xC5 -> { // multianewarray
                "TYPE_197:${cls.resolveClassName(insn.operand and U16_MASK)}:${insn.operand ushr 16}"
            }
            else -> "OP_${insn.opcode}:${insn.operand}"
        }
    }

    private fun walkBytecode(cls: DetailedClass, code: ByteArray): List<BytecodeInsn> {
        val result = ArrayList<BytecodeInsn>()
        var pc = 0
        while (pc < code.size) {
            val opcode = code[pc].toInt() and 0xFF
            val length: Int
            var operand = 0
            var intConst: Int? = null

            when (opcode) {
                in 0x02..0x08 -> { // iconst_m1 .. iconst_5
                    length = 1
                    intConst = opcode - 0x03
                }
                0x10 -> { // bipush
                    length = 2
                    if (pc + 1 < code.size) {
                        intConst = code[pc + 1].toInt()
                        operand = intConst
                    }
                }
                0x11 -> { // sipush
                    length = 3
                    if (pc + 2 < code.size) {
                        intConst = (code[pc + 1].toInt() shl 8) or (code[pc + 2].toInt() and 0xFF)
                        operand = intConst
                    }
                }
                0x12 -> { // ldc
                    length = 2
                    if (pc + 1 < code.size) {
                        operand = code[pc + 1].toInt() and 0xFF
                        intConst = cls.constValByCp[operand]?.removePrefix("INT:")?.toIntOrNull()
                    }
                }
                0x13, 0x14 -> { // ldc_w, ldc2_w
                    length = 3
                    if (pc + 2 < code.size) {
                        operand = ((code[pc + 1].toInt() and 0xFF) shl 8) or (code[pc + 2].toInt() and 0xFF)
                        if (opcode == 0x13) {
                            intConst = cls.constValByCp[operand]?.removePrefix("INT:")?.toIntOrNull()
                        }
                    }
                }
                in 0x15..0x19, in 0x36..0x3A, 0xA9, 0xBC -> {
                    length = 2
                    if (pc + 1 < code.size) operand = code[pc + 1].toInt() and 0xFF
                }
                0x84, in 0x99..0xA8, in 0xB2..0xB8, 0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7 -> {
                    length = 3
                    if (pc + 2 < code.size) {
                        operand = ((code[pc + 1].toInt() and 0xFF) shl 8) or (code[pc + 2].toInt() and 0xFF)
                    }
                }
                0xB9, 0xBA -> {
                    length = 5
                    if (pc + 2 < code.size) {
                        operand = ((code[pc + 1].toInt() and 0xFF) shl 8) or (code[pc + 2].toInt() and 0xFF)
                    }
                }
                0xC8, 0xC9 -> {
                    length = 5
                    if (pc + 5 <= code.size) {
                        operand = readS4(code, pc + 1)
                    }
                }
                0xC5 -> {
                    length = 4
                    if (pc + 3 < code.size) {
                        val cpIdx = ((code[pc + 1].toInt() and 0xFF) shl 8) or (code[pc + 2].toInt() and 0xFF)
                        val dims = code[pc + 3].toInt() and 0xFF
                        operand = cpIdx or (dims shl 16)
                    }
                }
                0xAA -> { // tableswitch
                    val aligned = (pc + 4) and -4
                    if (aligned + 12 > code.size) break
                    val low = readS4(code, aligned + 4)
                    val high = readS4(code, aligned + 8)
                    val count = (high - low + 1).coerceAtLeast(0)
                    length = (aligned - pc) + 12 + count * 4
                    operand = hashOperandBytes(code, pc + 1, minOf(code.size, pc + length))
                }
                0xAB -> { // lookupswitch
                    val aligned = (pc + 4) and -4
                    if (aligned + 8 > code.size) break
                    val npairs = readS4(code, aligned + 4).coerceAtLeast(0)
                    length = (aligned - pc) + 8 + npairs * 8
                    operand = hashOperandBytes(code, pc + 1, minOf(code.size, pc + length))
                }
                0xC4 -> { // wide
                    val subOp = if (pc + 1 < code.size) code[pc + 1].toInt() and 0xFF else 0
                    length = if (subOp == 0x84) 6 else 4
                    operand = hashOperandBytes(code, pc + 1, minOf(code.size, pc + length))
                }
                else -> length = 1
            }

            if (length <= 0 || pc + length > code.size) break
            result.add(
                BytecodeInsn(
                    pc = pc,
                    length = length,
                    opcode = opcode,
                    operand = operand,
                    intConstant = intConst
                )
            )
            pc += length
        }
        return result
    }

    private fun hashOperandBytes(code: ByteArray, start: Int, end: Int): Int {
        var h = 1
        for (idx in start..<end) {
            h = 31 * h + (code[idx].toInt() and 0xFF)
        }
        return h
    }

    private fun getMaxBranchTarget(code: ByteArray, insn: BytecodeInsn): Int {
        return when (insn.opcode) {
            in 0x99..0xA8, 0xC6, 0xC7 -> {
                val offset = insn.operand.toShort().toInt()
                insn.pc + offset
            }
            0xC8, 0xC9 -> {
                if (insn.pc + 5 <= code.size) insn.pc + readS4(code, insn.pc + 1) else -1
            }
            else -> -1
        }
    }

    private fun extractLocalStoreIndex(insn: BytecodeInsn): Int? = when (insn.opcode) {
        in 0x36..0x3A -> insn.operand
        in 0x3B..0x3E -> insn.opcode - 0x3B
        in 0x3F..0x42 -> insn.opcode - 0x3F
        in 0x43..0x46 -> insn.opcode - 0x43
        in 0x47..0x4A -> insn.opcode - 0x47
        in 0x4B..0x4E -> insn.opcode - 0x4B
        else -> null
    }

    private fun extractLocalLoadIndex(insn: BytecodeInsn): Int? = when (insn.opcode) {
        in 0x15..0x19 -> insn.operand
        in 0x1A..0x1D -> insn.opcode - 0x1A
        in 0x1E..0x21 -> insn.opcode - 0x1E
        in 0x22..0x25 -> insn.opcode - 0x22
        in 0x26..0x29 -> insn.opcode - 0x26
        in 0x2A..0x2D -> insn.opcode - 0x2A
        else -> null
    }

    private fun parseDetailedClass(bytes: ByteArray): DetailedClass? {
        if (bytes.size < MIN_CLASS_BYTES) return null
        val buf = ByteBuffer.wrap(bytes)
        if (buf.int != CLASS_MAGIC) return null
        buf.short // minor
        buf.short // major
        val cpCount = buf.short.toInt() and U16_MASK
        val cpStartOffset = buf.position()

        val utf8Pool = HashMap<Int, String>()
        val classNameIdxByCp = HashMap<Int, Int>()
        val classNameOffsetByCp = HashMap<Int, Int>()
        val rawConstByCp = HashMap<Int, Any>()
        val stringUtf8IdxByCp = HashMap<Int, Int>()
        val methodTypeDescIdxByCp = HashMap<Int, Int>()
        val memberRefByCp = HashMap<Int, Triple<Int, Int, Int>>()
        val nameAndTypeByCp = HashMap<Int, Pair<Int, Int>>()
        val invokeDynamicByCp = HashMap<Int, Pair<Int, Int>>()
        val methodHandleByCp = HashMap<Int, Pair<Int, Int>>()

        var slot = 1
        while (slot < cpCount) {
            val tag = buf.get().toInt() and 0xFF
            when (tag) {
                CP_TAG_UTF8 -> {
                    val len = buf.short.toInt() and U16_MASK
                    val strBytes = ByteArray(len)
                    buf.get(strBytes)
                    utf8Pool[slot] = String(strBytes, Charsets.UTF_8)
                    slot += 1
                }
                CP_TAG_CLASS -> {
                    classNameOffsetByCp[slot] = buf.position()
                    classNameIdxByCp[slot] = buf.short.toInt() and U16_MASK
                    slot += 1
                }
                CP_TAG_STRING -> {
                    stringUtf8IdxByCp[slot] = buf.short.toInt() and U16_MASK
                    slot += 1
                }
                CP_TAG_INTEGER -> {
                    rawConstByCp[slot] = buf.int
                    slot += 1
                }
                CP_TAG_FLOAT -> {
                    rawConstByCp[slot] = buf.float
                    slot += 1
                }
                CP_TAG_LONG -> {
                    rawConstByCp[slot] = buf.long
                    slot += 2
                }
                CP_TAG_DOUBLE -> {
                    rawConstByCp[slot] = buf.double
                    slot += 2
                }
                CP_TAG_FIELDREF, CP_TAG_METHODREF, CP_TAG_INTERFACE_METHODREF -> {
                    val clsIdx = buf.short.toInt() and U16_MASK
                    val natIdx = buf.short.toInt() and U16_MASK
                    memberRefByCp[slot] = Triple(tag, clsIdx, natIdx)
                    slot += 1
                }
                CP_TAG_NAME_AND_TYPE -> {
                    val nameIdx = buf.short.toInt() and U16_MASK
                    val descIdx = buf.short.toInt() and U16_MASK
                    nameAndTypeByCp[slot] = nameIdx to descIdx
                    slot += 1
                }
                CP_TAG_METHOD_HANDLE -> {
                    val kind = buf.get().toInt() and 0xFF
                    val refIdx = buf.short.toInt() and U16_MASK
                    methodHandleByCp[slot] = kind to refIdx
                    slot += 1
                }
                CP_TAG_DYNAMIC, CP_TAG_INVOKE_DYNAMIC -> {
                    val bsmIdx = buf.short.toInt() and U16_MASK
                    val natIdx = buf.short.toInt() and U16_MASK
                    if (tag == CP_TAG_INVOKE_DYNAMIC) {
                        invokeDynamicByCp[slot] = bsmIdx to natIdx
                    }
                    slot += 1
                }
                CP_TAG_METHOD_TYPE -> {
                    methodTypeDescIdxByCp[slot] = buf.short.toInt() and U16_MASK
                    slot += 1
                }
                CP_TAG_MODULE, CP_TAG_PACKAGE -> {
                    buf.position(buf.position() + 2)
                    slot += 1
                }
                else -> return null
            }
        }
        val cpEndOffset = buf.position()

        val constValByCp = HashMap<Int, String>()
        for ((idx, v) in rawConstByCp) {
            constValByCp[idx] = when (v) {
                is Int -> "INT:$v"
                is Long -> v.toString()
                is Float -> v.toString()
                is Double -> v.toString()
                else -> v.toString()
            }
        }
        for ((idx, utf8Idx) in stringUtf8IdxByCp) {
            val str = utf8Pool[utf8Idx].orEmpty()
            val hex = str.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(Locale.US, it) }
            constValByCp[idx] = "HEX:$hex"
        }
        for ((idx, descIdx) in methodTypeDescIdxByCp) {
            constValByCp[idx] = "MT:${utf8Pool[descIdx].orEmpty()}"
        }

        val accessFlags = buf.short.toInt() and U16_MASK
        val thisClassCpIdx = buf.short.toInt() and U16_MASK
        val superClassCpIdx = buf.short.toInt() and U16_MASK
        val thisInternalName = classNameIdxByCp[thisClassCpIdx]?.let { utf8Pool[it] }.orEmpty()
        val superInternalName = classNameIdxByCp[superClassCpIdx]?.let { utf8Pool[it] }.orEmpty()

        val interfacesCount = buf.short.toInt() and U16_MASK
        val interfaces = ArrayList<String>(interfacesCount)
        repeat(interfacesCount) {
            val ifaceCpIdx = buf.short.toInt() and U16_MASK
            val ifaceName = classNameIdxByCp[ifaceCpIdx]?.let { utf8Pool[it] }.orEmpty()
            interfaces.add(ifaceName)
        }

        val fieldsCount = buf.short.toInt() and U16_MASK
        val fields = ArrayList<ParsedField>(fieldsCount)
        repeat(fieldsCount) {
            val fFlags = buf.short.toInt() and U16_MASK
            val fName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
            val fDesc = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
            val attrsCount = buf.short.toInt() and U16_MASK
            var constValue: String? = null
            var visibleAnnsSig = ""
            repeat(attrsCount) {
                val attrName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
                val attrLen = buf.int
                val attrEnd = buf.position() + attrLen
                if (attrName == "ConstantValue" && attrLen == 2) {
                    val cvCpIdx = buf.short.toInt() and U16_MASK
                    val raw = constValByCp[cvCpIdx]
                    constValue = if (raw != null && raw.startsWith("INT:")) {
                        raw.removePrefix("INT:")
                    } else {
                        raw
                    }
                } else if (attrName == "RuntimeVisibleAnnotations" && attrLen >= 2) {
                    visibleAnnsSig = parseVisibleAnnotationsSig(
                        buf,
                        attrEnd,
                        utf8Pool,
                        constValByCp,
                        classNameIdxByCp
                    )
                }
                buf.position(attrEnd)
            }
            fields.add(ParsedField(fFlags, fName, fDesc, constValue, visibleAnnsSig))
        }

        val methodsCountOffset = buf.position()
        val methodsCount = buf.short.toInt() and U16_MASK
        val methods = ArrayList<ParsedMethod>(methodsCount)
        repeat(methodsCount) {
            val mFlags = buf.short.toInt() and U16_MASK
            val mNameIdx = buf.short.toInt() and U16_MASK
            val mName = utf8Pool[mNameIdx].orEmpty()
            val mDesc = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
            val attrsCount = buf.short.toInt() and U16_MASK
            var codeBytes: ByteArray? = null
            var codeOffsetInClass = -1
            var codeAttrStartOffset = -1
            var codeAttrEndOffset = -1
            var hasInlineMarker = false
            var hasStackMapTable = false
            var visibleAnnsSig = ""
            val exceptionRanges = mutableListOf<IntRange>()
            val exceptionEntries = mutableListOf<String>()

            repeat(attrsCount) {
                val attrStart = buf.position()
                val attrName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
                val attrLen = buf.int
                val attrEnd = buf.position() + attrLen
                if (attrName == "Code" && attrLen >= 12) {
                    codeAttrStartOffset = attrStart
                    codeAttrEndOffset = attrEnd
                    buf.short // max_stack
                    buf.short // max_locals
                    val codeLen = buf.int
                    codeOffsetInClass = buf.position()
                    val cBytes = ByteArray(codeLen)
                    buf.get(cBytes)
                    codeBytes = cBytes
                    val excLen = buf.short.toInt() and U16_MASK
                    repeat(excLen) {
                        val sPc = buf.short.toInt() and U16_MASK
                        val ePc = buf.short.toInt() and U16_MASK
                        val hPc = buf.short.toInt() and U16_MASK
                        val catchTypeCpIdx = buf.short.toInt() and U16_MASK
                        val catchTypeName = classNameIdxByCp[catchTypeCpIdx]?.let { utf8Pool[it] }.orEmpty()
                        exceptionRanges.add(sPc..ePc)
                        exceptionEntries.add("$sPc:$ePc:$hPc:$catchTypeName")
                    }
                    val codeAttrs = buf.short.toInt() and U16_MASK
                    repeat(codeAttrs) {
                        val subAttrName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
                        val subAttrLen = buf.int
                        val subAttrEnd = buf.position() + subAttrLen
                        if (subAttrName == "StackMapTable") {
                            hasStackMapTable = true
                        } else if (subAttrName == "LocalVariableTable" && subAttrLen >= 2) {
                            if (containsInlineLocalMarker(buf, utf8Pool, mName)) {
                                hasInlineMarker = true
                            }
                        }
                        buf.position(subAttrEnd)
                    }
                } else if (attrName == "RuntimeVisibleAnnotations" && attrLen >= 2) {
                    visibleAnnsSig = parseVisibleAnnotationsSig(
                        buf,
                        attrEnd,
                        utf8Pool,
                        constValByCp,
                        classNameIdxByCp
                    )
                }
                buf.position(attrEnd)
            }
            methods.add(
                ParsedMethod(
                    accessFlags = mFlags,
                    name = mName,
                    descriptor = mDesc,
                    nameCpIdx = mNameIdx,
                    codeBytes = codeBytes,
                    codeOffsetInClass = codeOffsetInClass,
                    codeAttrStartOffset = codeAttrStartOffset,
                    codeAttrEndOffset = codeAttrEndOffset,
                    hasInlineMarker = hasInlineMarker,
                    hasStackMapTable = hasStackMapTable,
                    exceptionRanges = exceptionRanges,
                    exceptionTableSig = exceptionEntries.joinToString(","),
                    visibleAnnotationsSig = visibleAnnsSig
                )
            )
        }
        val methodsEndOffset = buf.position()

        val bootstrapTargets = HashMap<Int, Pair<String, String>>()
        val bootstrapArgsSummary = HashMap<Int, String>()
        var bootstrapAttrStartOffset = -1
        var bootstrapAttrEndOffset = -1
        var classVisibleAnnsSig = ""
        var hasHiltAnnotation = utf8Pool.values.any { it in hiltAnnotations }
        val classAttrsCount = buf.short.toInt() and U16_MASK
        repeat(classAttrsCount) {
            val attrStart = buf.position()
            val attrName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
            val attrLen = buf.int
            val attrEnd = buf.position() + attrLen
            if (attrName == "BootstrapMethods" && attrLen >= 2) {
                bootstrapAttrStartOffset = attrStart
                bootstrapAttrEndOffset = attrEnd
                parseBootstrapMethodsAttribute(
                    buf = buf,
                    thisInternalName = thisInternalName,
                    utf8Pool = utf8Pool,
                    classNameIdxByCp = classNameIdxByCp,
                    constValByCp = constValByCp,
                    memberRefByCp = memberRefByCp,
                    nameAndTypeByCp = nameAndTypeByCp,
                    methodHandleByCp = methodHandleByCp,
                    bootstrapTargets = bootstrapTargets,
                    bootstrapArgsSummary = bootstrapArgsSummary
                )
            } else if (attrName == "RuntimeVisibleAnnotations" && attrLen >= 2) {
                val sig = parseVisibleAnnotationsSig(buf, attrEnd, utf8Pool, constValByCp, classNameIdxByCp)
                classVisibleAnnsSig = sig
                if (utf8Pool.values.any { it in hiltAnnotations }) {
                    hasHiltAnnotation = true
                }
            } else if (attrName == "RuntimeInvisibleAnnotations" && attrLen >= 2) {
                val numAnns = buf.short.toInt() and U16_MASK
                if (numAnns > 0 && utf8Pool.values.any { it in hiltAnnotations }) {
                    hasHiltAnnotation = true
                }
            }
            buf.position(attrEnd)
        }

        return DetailedClass(
            rawBytes = bytes,
            cpCount = cpCount,
            cpStartOffset = cpStartOffset,
            cpEndOffset = cpEndOffset,
            utf8Pool = utf8Pool,
            classNameIdxByCp = classNameIdxByCp,
            classNameOffsetByCp = classNameOffsetByCp,
            constValByCp = constValByCp,
            memberRefByCp = memberRefByCp,
            nameAndTypeByCp = nameAndTypeByCp,
            invokeDynamicByCp = invokeDynamicByCp,
            methodHandleByCp = methodHandleByCp,
            accessFlags = accessFlags,
            thisClassCpIdx = thisClassCpIdx,
            thisInternalName = thisInternalName,
            superClassCpIdx = superClassCpIdx,
            superInternalName = superInternalName,
            interfaces = interfaces,
            fields = fields,
            methods = methods,
            methodsCountOffset = methodsCountOffset,
            methodsEndOffset = methodsEndOffset,
            bootstrapTargets = bootstrapTargets,
            bootstrapArgsSummary = bootstrapArgsSummary,
            bootstrapAttrStartOffset = bootstrapAttrStartOffset,
            bootstrapAttrEndOffset = bootstrapAttrEndOffset,
            visibleAnnotationsSig = classVisibleAnnsSig,
            hasHiltAnnotation = hasHiltAnnotation
        )
    }

    private fun containsInlineLocalMarker(
        buf: ByteBuffer,
        utf8Pool: Map<Int, String>,
        methodName: String
    ): Boolean {
        val lvCount = buf.short.toInt() and U16_MASK
        var found = false
        val cleanMethod = methodName.substringBefore('-').substringBefore('$')
        repeat(lvCount) {
            val startPc = buf.short.toInt() and U16_MASK
            buf.short // length
            val lvName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
            buf.short // descriptor_index
            buf.short // index
            if (lvName.startsWith("\$i\$f\$") && startPc <= 6) {
                val markerTarget = lvName.removePrefix("\$i\$f\$").replace("-", "")
                if (markerTarget.equals(cleanMethod, ignoreCase = true)) {
                    found = true
                }
            }
        }
        return found
    }

    private fun parseBootstrapMethodsAttribute(
        buf: ByteBuffer,
        thisInternalName: String,
        utf8Pool: Map<Int, String>,
        classNameIdxByCp: Map<Int, Int>,
        constValByCp: Map<Int, String>,
        memberRefByCp: Map<Int, Triple<Int, Int, Int>>,
        nameAndTypeByCp: Map<Int, Pair<Int, Int>>,
        methodHandleByCp: Map<Int, Pair<Int, Int>>,
        bootstrapTargets: MutableMap<Int, Pair<String, String>>,
        bootstrapArgsSummary: MutableMap<Int, String>
    ) {
        val numBsm = buf.short.toInt() and U16_MASK
        for (bsmIdx in 0..<numBsm) {
            val bsmMhCpIdx = buf.short.toInt() and U16_MASK
            val numArgs = buf.short.toInt() and U16_MASK
            val argSummaries = ArrayList<String>(numArgs + 1)
            methodHandleByCp[bsmMhCpIdx]?.let { mh ->
                memberRefByCp[mh.second]?.let { mRef ->
                    val owner = classNameIdxByCp[mRef.second]?.let { utf8Pool[it] }.orEmpty()
                    val nat = nameAndTypeByCp[mRef.third]
                    val mName = nat?.let { utf8Pool[it.first] }.orEmpty()
                    argSummaries.add("BSM:$owner.$mName")
                }
            }
            repeat(numArgs) {
                val argCpIdx = buf.short.toInt() and U16_MASK
                val mh = methodHandleByCp[argCpIdx]
                val mRef = mh?.let { memberRefByCp[it.second] }
                if (mRef != null) {
                    val owner = classNameIdxByCp[mRef.second]?.let { utf8Pool[it] }.orEmpty()
                    val nat = nameAndTypeByCp[mRef.third]
                    if (nat != null) {
                        val targetName = utf8Pool[nat.first].orEmpty()
                        val targetDesc = utf8Pool[nat.second].orEmpty()
                        argSummaries.add("MH:$owner.$targetName:$targetDesc")
                        if (owner == thisInternalName) {
                            bootstrapTargets[bsmIdx] = targetName to targetDesc
                        }
                    }
                } else {
                    val constSummary = constValByCp[argCpIdx]
                        ?: classNameIdxByCp[argCpIdx]?.let { utf8Pool[it] }
                        ?: utf8Pool[argCpIdx]
                        ?: argCpIdx.toString()
                    argSummaries.add(constSummary)
                }
            }
            bootstrapArgsSummary[bsmIdx] = argSummaries.joinToString(",")
        }
    }

    private fun parseVisibleAnnotationsSig(
        buf: ByteBuffer,
        attrEnd: Int,
        utf8Pool: Map<Int, String>,
        constValByCp: Map<Int, String>,
        classNameIdxByCp: Map<Int, Int>
    ): String = runCatching {
        val numAnns = buf.short.toInt() and U16_MASK
        val parts = ArrayList<String>(numAnns)
        repeat(numAnns) {
            if (buf.position() + 4 > attrEnd) return@runCatching parts.joinToString(";")
            val (typeDesc, annText) = readSingleAnnotation(buf, attrEnd, utf8Pool, constValByCp, classNameIdxByCp)
            if (typeDesc.isNotEmpty() && typeDesc !in ignoredDebugAnnotations) {
                parts.add(annText)
            }
        }
        parts.joinToString(";")
    }.getOrDefault("")

    private fun readSingleAnnotation(
        buf: ByteBuffer,
        attrEnd: Int,
        utf8Pool: Map<Int, String>,
        constValByCp: Map<Int, String>,
        classNameIdxByCp: Map<Int, Int>
    ): Pair<String, String> {
        val typeDesc = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
        val numPairs = buf.short.toInt() and U16_MASK
        val pairs = ArrayList<String>(numPairs)
        repeat(numPairs) {
            if (buf.position() + 3 > attrEnd) return typeDesc to "@$typeDesc(${pairs.joinToString(",")})"
            val elemName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
            val elemVal = readAnnotationElementValue(buf, attrEnd, utf8Pool, constValByCp, classNameIdxByCp)
            pairs.add("$elemName=$elemVal")
        }
        return typeDesc to "@$typeDesc(${pairs.joinToString(",")})"
    }

    private fun readAnnotationElementValue(
        buf: ByteBuffer,
        attrEnd: Int,
        utf8Pool: Map<Int, String>,
        constValByCp: Map<Int, String>,
        classNameIdxByCp: Map<Int, Int>
    ): String {
        if (buf.position() >= attrEnd) return ""
        return when (val tag = (buf.get().toInt() and 0xFF).toChar()) {
            'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's' -> {
                val cpIdx = buf.short.toInt() and U16_MASK
                constValByCp[cpIdx] ?: utf8Pool[cpIdx] ?: cpIdx.toString()
            }
            'e' -> {
                val enumType = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
                val enumConst = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
                "$enumType.$enumConst"
            }
            'c' -> {
                val clsIdx = buf.short.toInt() and U16_MASK
                utf8Pool[clsIdx] ?: classNameIdxByCp[clsIdx]?.let { utf8Pool[it] } ?: clsIdx.toString()
            }
            '@' -> readSingleAnnotation(buf, attrEnd, utf8Pool, constValByCp, classNameIdxByCp).second
            '[' -> {
                val count = buf.short.toInt() and U16_MASK
                val items = ArrayList<String>(count)
                repeat(count) {
                    items.add(readAnnotationElementValue(buf, attrEnd, utf8Pool, constValByCp, classNameIdxByCp))
                }
                "[${items.joinToString(",")}]"
            }
            else -> tag.toString()
        }
    }

    private fun readS4(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun writeU2(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU4(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
