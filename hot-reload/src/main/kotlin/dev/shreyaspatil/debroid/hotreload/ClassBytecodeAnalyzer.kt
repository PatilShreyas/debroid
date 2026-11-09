package dev.shreyaspatil.debroid.hotreload

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
        val resetAllClassScopes: Boolean = false
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

    private const val ACC_PRIVATE = 0x0002
    private const val ACC_STATIC = 0x0008
    private const val ACC_SYNTHETIC = 0x1000

    private const val COMPOSER_DESC = "Landroidx/compose/runtime/Composer;"
    private const val COMPOSER_OWNER = "androidx/compose/runtime/Composer"
    private const val COMPOSABLE_LAMBDA_KT = "androidx/compose/runtime/internal/ComposableLambdaKt"
    private val anonymousClassSuffixRegex = Regex("""\$\d+""")
    private val hiltAnnotations = setOf(
        "Ldagger/hilt/android/AndroidEntryPoint;",
        "Ldagger/hilt/android/HiltAndroidApp;"
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
        val constantValue: String?
    )

    private class ParsedMethod(
        val accessFlags: Int,
        val name: String,
        val descriptor: String,
        val codeBytes: ByteArray?,
        val codeOffsetInClass: Int,
        val hasInlineMarker: Boolean,
        val hasStackMapTable: Boolean,
        val exceptionRanges: List<IntRange>
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
        val bootstrapTargets: Map<Int, Pair<String, String>>,
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

        return ComposeRememberDiff(
            resetScopeKeys = resetKeys,
            resetAllClassScopes = resetAll
        )
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
                        val windowStart = maxOf(0, idx - 12)
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
                                val blockSig = blockInsns.joinToString(",") {
                                    formatInsnSignature(cls, it, methodsByKey)
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
                        signature.add("CALL:$owner.$name:$desc|$lambdaSig")
                        pendingChanged.clear()
                    } else if (desc.contains(COMPOSER_DESC)) {
                        pendingChanged.clear()
                    }
                }
            }
            i++
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
                return normalizeMethodInstructions(cls, targetMethod)
            }
        }
        return ""
    }

    private fun normalizeMethodInstructions(cls: DetailedClass, method: ParsedMethod): String {
        val code = method.codeBytes ?: return ""
        val insns = walkBytecode(cls, code)
        val methodsByKey = cls.methods.associateBy { it.name to it.descriptor }
        return insns.joinToString(";") { formatInsnSignature(cls, it, methodsByKey, expandIndy = false) }
    }

    private fun formatInsnSignature(
        cls: DetailedClass,
        insn: BytecodeInsn,
        methodsByKey: Map<Pair<String, String>, ParsedMethod>,
        expandIndy: Boolean = true
    ): String {
        if (insn.intConstant != null) {
            return "OP_${insn.opcode}:${insn.intConstant}"
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
                    if (expandIndy && ref.first == cls.thisInternalName && ref.second != "<clinit>") {
                        val targetMethod = methodsByKey[ref.second to ref.third]
                        if (targetMethod != null) {
                            "$base[${normalizeMethodInstructions(cls, targetMethod)}]"
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
                    methodsByKey[target]?.let { normalizeMethodInstructions(cls, it) }.orEmpty()
                } else {
                    target?.toString().orEmpty()
                }
                "INDY:$name:$desc[$targetSig]"
            }
            0xBB, 0xBD, 0xC0, 0xC1 -> { // new, anewarray, checkcast, instanceof
                "TYPE_${insn.opcode}:${cls.resolveClassName(insn.operand)}"
            }
            else -> "OP_${insn.opcode}"
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
                0xB9, 0xBA, 0xC8, 0xC9 -> {
                    length = 5
                    if (pc + 2 < code.size) {
                        operand = ((code[pc + 1].toInt() and 0xFF) shl 8) or (code[pc + 2].toInt() and 0xFF)
                    }
                }
                0xC5 -> {
                    length = 4
                    if (pc + 2 < code.size) {
                        operand = ((code[pc + 1].toInt() and 0xFF) shl 8) or (code[pc + 2].toInt() and 0xFF)
                    }
                }
                0xAA -> { // tableswitch
                    val aligned = (pc + 4) and -4
                    if (aligned + 12 > code.size) break
                    val low = readS4(code, aligned + 4)
                    val high = readS4(code, aligned + 8)
                    val count = (high - low + 1).coerceAtLeast(0)
                    length = (aligned - pc) + 12 + count * 4
                }
                0xAB -> { // lookupswitch
                    val aligned = (pc + 4) and -4
                    if (aligned + 8 > code.size) break
                    val npairs = readS4(code, aligned + 4).coerceAtLeast(0)
                    length = (aligned - pc) + 8 + npairs * 8
                }
                0xC4 -> { // wide
                    val subOp = if (pc + 1 < code.size) code[pc + 1].toInt() and 0xFF else 0
                    length = if (subOp == 0x84) 6 else 4
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
                CP_TAG_METHOD_TYPE, CP_TAG_MODULE, CP_TAG_PACKAGE -> {
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
                }
                buf.position(attrEnd)
            }
            fields.add(ParsedField(fFlags, fName, fDesc, constValue))
        }

        val methodsCount = buf.short.toInt() and U16_MASK
        val methods = ArrayList<ParsedMethod>(methodsCount)
        repeat(methodsCount) {
            val mFlags = buf.short.toInt() and U16_MASK
            val mName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
            val mDesc = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
            val attrsCount = buf.short.toInt() and U16_MASK
            var codeBytes: ByteArray? = null
            var codeOffsetInClass = -1
            var hasInlineMarker = false
            var hasStackMapTable = false
            val exceptionRanges = mutableListOf<IntRange>()

            repeat(attrsCount) {
                val attrName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
                val attrLen = buf.int
                val attrEnd = buf.position() + attrLen
                if (attrName == "Code" && attrLen >= 12) {
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
                        buf.short // handler_pc
                        buf.short // catch_type
                        exceptionRanges.add(sPc..ePc)
                    }
                    val codeAttrs = buf.short.toInt() and U16_MASK
                    repeat(codeAttrs) {
                        val subAttrName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
                        val subAttrLen = buf.int
                        val subAttrEnd = buf.position() + subAttrLen
                        if (subAttrName == "StackMapTable") {
                            hasStackMapTable = true
                        } else if (subAttrName == "LocalVariableTable" && subAttrLen >= 2) {
                            val lvCount = buf.short.toInt() and U16_MASK
                            repeat(lvCount) {
                                buf.short // start_pc
                                buf.short // length
                                val lvName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
                                buf.short // descriptor_index
                                buf.short // index
                                if (lvName.startsWith("\$i\$f\$")) {
                                    hasInlineMarker = true
                                }
                            }
                        }
                        buf.position(subAttrEnd)
                    }
                }
                buf.position(attrEnd)
            }
            methods.add(
                ParsedMethod(
                    accessFlags = mFlags,
                    name = mName,
                    descriptor = mDesc,
                    codeBytes = codeBytes,
                    codeOffsetInClass = codeOffsetInClass,
                    hasInlineMarker = hasInlineMarker,
                    hasStackMapTable = hasStackMapTable,
                    exceptionRanges = exceptionRanges
                )
            )
        }

        val bootstrapTargets = HashMap<Int, Pair<String, String>>()
        var hasHiltAnnotation = utf8Pool.values.any { it in hiltAnnotations }
        val classAttrsCount = buf.short.toInt() and U16_MASK
        repeat(classAttrsCount) {
            val attrName = utf8Pool[buf.short.toInt() and U16_MASK].orEmpty()
            val attrLen = buf.int
            val attrEnd = buf.position() + attrLen
            if (attrName == "BootstrapMethods" && attrLen >= 2) {
                val numBsm = buf.short.toInt() and U16_MASK
                for (bsmIdx in 0..<numBsm) {
                    buf.short // bootstrap_method_ref
                    val numArgs = buf.short.toInt() and U16_MASK
                    repeat(numArgs) {
                        val argCpIdx = buf.short.toInt() and U16_MASK
                        val mh = methodHandleByCp[argCpIdx]
                        val mRef = mh?.let { memberRefByCp[it.second] }
                        if (mRef != null) {
                            val owner = classNameIdxByCp[mRef.second]?.let { utf8Pool[it] }.orEmpty()
                            val nat = nameAndTypeByCp[mRef.third]
                            if (owner == thisInternalName && nat != null) {
                                val targetName = utf8Pool[nat.first].orEmpty()
                                val targetDesc = utf8Pool[nat.second].orEmpty()
                                bootstrapTargets[bsmIdx] = targetName to targetDesc
                            }
                        }
                    }
                }
            } else if ((attrName == "RuntimeInvisibleAnnotations" || attrName == "RuntimeVisibleAnnotations") &&
                attrLen >= 2
            ) {
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
            bootstrapTargets = bootstrapTargets,
            hasHiltAnnotation = hasHiltAnnotation
        )
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
}
