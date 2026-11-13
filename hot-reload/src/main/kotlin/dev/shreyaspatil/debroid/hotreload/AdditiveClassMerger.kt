package dev.shreyaspatil.debroid.hotreload

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Merges removed fields and methods from a previous version of a class/DEX into the newly
 * compiled `.class` file as no-op stubs so that ART's `com.android.art.class.structurally_redefine_classes`
 * (which only permits additive schema changes) can hot-swap classes even when lambdas, composable
 * singletons, or helper functions are removed or reordered across edits.
 */
@Suppress("MagicNumber", "TooManyFunctions")
object AdditiveClassMerger {
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
    private const val ACC_ABSTRACT_OR_NATIVE = 0x0500

    private const val OP_ACONST_NULL: Byte = 0x01
    private const val OP_ICONST_0: Byte = 0x03
    private const val OP_LCONST_0: Byte = 0x09
    private const val OP_FCONST_0: Byte = 0x0B
    private const val OP_DCONST_0: Byte = 0x0E
    private const val OP_IRETURN: Byte = 0xAC.toByte()
    private const val OP_LRETURN: Byte = 0xAD.toByte()
    private const val OP_FRETURN: Byte = 0xAE.toByte()
    private const val OP_DRETURN: Byte = 0xAF.toByte()
    private const val OP_ARETURN: Byte = 0xB0.toByte()
    private const val OP_RETURN: Byte = 0xB1.toByte()

    data class MemberSignature(
        val accessFlags: Int,
        val name: String,
        val descriptor: String,
        val accessFlagsOffset: Int = -1
    )

    data class DexClassMembers(
        val fields: List<MemberSignature>,
        val methods: List<MemberSignature>,
        val superClassName: String? = null
    )

    private class ConstantPoolSection(
        val count: Int,
        val startOffset: Int,
        val endOffset: Int,
        val utf8Pool: Map<Int, String>
    )

    private class MemberSection(
        val startOffset: Int,
        val count: Int,
        val endOffset: Int,
        val members: List<MemberSignature>
    )

    private class ParsedClass(
        val rawBytes: ByteArray,
        val cp: ConstantPoolSection,
        val fields: MemberSection,
        val methods: MemberSection
    )

    fun mergePreservingRemovedMembers(previousBytes: ByteArray, currentBytes: ByteArray): ByteArray {
        val hiltTransformed = ClassBytecodeAnalyzer.applyHiltTransformIfNeeded(currentBytes)
        val prev = runCatching { parseClass(previousBytes) }.getOrNull() ?: return hiltTransformed
        val curr = runCatching { parseClass(hiltTransformed) }.getOrNull() ?: return hiltTransformed

        val currentFieldKeys = curr.fields.members.map { it.name to it.descriptor }.toSet()
        val missingFields = prev.fields.members.filter { (it.name to it.descriptor) !in currentFieldKeys }

        val currentMethodKeys = curr.methods.members.map { it.name to it.descriptor }.toSet()
        val missingMethods = prev.methods.members
            .filter { it.name != "<init>" && it.name != "<clinit>" && (it.name to it.descriptor) !in currentMethodKeys }
            .map { normalizeLambdaStubFlags(it) }

        if (missingFields.isEmpty() && missingMethods.isEmpty()) {
            return hiltTransformed
        }

        return rebuildWithMissingMembers(curr, missingFields, missingMethods)
    }

    fun mergeWithPreviousDex(
        previousDexBytes: ByteArray,
        currentDexBytes: ByteArray,
        currentClassBytes: ByteArray,
        targetClassName: String? = null
    ): ByteArray {
        val prevDex = runCatching { parseDexMembers(previousDexBytes, targetClassName) }.getOrNull()
            ?: return ClassBytecodeAnalyzer.applyHiltTransformIfNeeded(currentClassBytes)
        val currDex = runCatching { parseDexMembers(currentDexBytes, targetClassName) }.getOrNull()
            ?: return ClassBytecodeAnalyzer.applyHiltTransformIfNeeded(currentClassBytes, prevDex.superClassName)
        return mergeWithPreviousDexMembers(prevDex, currDex, currentClassBytes)
    }

    fun mergeWithPreviousDexMembers(
        prevDex: DexClassMembers,
        currDex: DexClassMembers,
        currentClassBytes: ByteArray
    ): ByteArray {
        val hiltBytes = ClassBytecodeAnalyzer.applyHiltTransformIfNeeded(currentClassBytes, prevDex.superClassName)
        val patchedBytes = alignMethodAccessFlagsIfNeeded(prevDex, hiltBytes)
        val currClass = runCatching { parseClass(patchedBytes) }.getOrNull() ?: return patchedBytes

        val currFieldKeys = buildSet {
            currDex.fields.forEach { add(it.name to it.descriptor) }
            currClass.fields.members.forEach { add(it.name to it.descriptor) }
        }
        val missingFields = prevDex.fields.filter { (it.name to it.descriptor) !in currFieldKeys }

        val currMethodKeys = buildSet {
            currDex.methods.forEach { add(it.name to it.descriptor) }
            currClass.methods.members.forEach { add(it.name to it.descriptor) }
        }
        val missingMethods = prevDex.methods.filter {
            it.name != "<init>" && it.name != "<clinit>" && (it.name to it.descriptor) !in currMethodKeys
        }

        if (missingFields.isEmpty() && missingMethods.isEmpty()) {
            return patchedBytes
        }

        return rebuildWithMissingMembers(currClass, missingFields, missingMethods)
    }

    private fun alignMethodAccessFlagsIfNeeded(
        prevDex: DexClassMembers,
        currentClassBytes: ByteArray
    ): ByteArray {
        val currClass = runCatching { parseClass(currentClassBytes) }.getOrNull() ?: return currentClassBytes
        val prevMethodsByKey = prevDex.methods.associateBy { it.name to it.descriptor }
        var cloned: ByteArray? = null
        for (method in currClass.methods.members) {
            if (method.accessFlagsOffset < 0) continue
            val prevMethod = prevMethodsByKey[method.name to method.descriptor] ?: continue
            val currIsPrivate = (method.accessFlags and ACC_PRIVATE) != 0
            val prevIsNotPrivate = (prevMethod.accessFlags and ACC_PRIVATE) == 0
            if (currIsPrivate && prevIsNotPrivate) {
                val targetBytes = cloned ?: currentClassBytes.clone().also { cloned = it }
                val updatedFlags = method.accessFlags and ACC_PRIVATE.inv()
                targetBytes[method.accessFlagsOffset] = ((updatedFlags ushr 8) and 0xFF).toByte()
                targetBytes[method.accessFlagsOffset + 1] = (updatedFlags and 0xFF).toByte()
            }
        }
        return cloned ?: currentClassBytes
    }

    fun mergeWithKnownStubs(
        currentClassBytes: ByteArray,
        knownStubs: DexClassMembers
    ): ByteArray {
        val hiltBytes = ClassBytecodeAnalyzer.applyHiltTransformIfNeeded(currentClassBytes, knownStubs.superClassName)
        if (knownStubs.fields.isEmpty() && knownStubs.methods.isEmpty()) return hiltBytes
        val patchedBytes = alignMethodAccessFlagsIfNeeded(knownStubs, hiltBytes)
        val currClass = runCatching { parseClass(patchedBytes) }.getOrNull() ?: return patchedBytes

        val currFieldKeys = currClass.fields.members.map { it.name to it.descriptor }.toSet()
        val missingFields = knownStubs.fields.filter { (it.name to it.descriptor) !in currFieldKeys }

        val currMethodKeys = currClass.methods.members.map { it.name to it.descriptor }.toSet()
        val missingMethods = knownStubs.methods.filter {
            it.name != "<init>" &&
                it.name != "<clinit>" &&
                !it.name.startsWith("\$r8\$lambda\$") &&
                (it.name to it.descriptor) !in currMethodKeys
        }

        if (missingFields.isEmpty() && missingMethods.isEmpty()) {
            return patchedBytes
        }

        return rebuildWithMissingMembers(currClass, missingFields, missingMethods)
    }

    internal fun parseDexMembers(
        dexBytes: ByteArray,
        targetClassName: String? = null
    ): DexClassMembers? = DexMemberParser.parse(dexBytes, targetClassName)

    internal fun parseAllDexClasses(dexBytes: ByteArray): Map<String, DexClassMembers> =
        DexMemberParser.parseAllClasses(dexBytes)

    internal fun extractClassMembers(classBytes: ByteArray): DexClassMembers? {
        val parsed = runCatching { parseClass(classBytes) }.getOrNull() ?: return null
        val methods = parsed.methods.members.map { normalizeLambdaStubFlags(it) }
        return DexClassMembers(fields = parsed.fields.members, methods = methods)
    }

    private fun normalizeLambdaStubFlags(method: MemberSignature): MemberSignature {
        val isComposableSingletonLambda = method.name.startsWith("lambda_") && method.name.contains("\$lambda\$")
        val isPrivateSynthetic = (method.accessFlags and (ACC_PRIVATE or ACC_SYNTHETIC)) ==
            (ACC_PRIVATE or ACC_SYNTHETIC)
        return if (isComposableSingletonLambda || (isPrivateSynthetic && method.name.contains("\$lambda\$"))) {
            method.copy(accessFlags = method.accessFlags and (ACC_PRIVATE or ACC_SYNTHETIC).inv())
        } else {
            method
        }
    }

    private fun rebuildWithMissingMembers(
        curr: ParsedClass,
        missingFields: List<MemberSignature>,
        missingMethods: List<MemberSignature>
    ): ByteArray {
        val utf8ToIndex = curr.cp.utf8Pool.entries.associate { (idx, str) -> str to idx }.toMutableMap()
        var nextCpIndex = curr.cp.count
        val extraCpBytes = ByteArrayOutputStream()

        fun getOrAddUtf8(value: String): Int {
            utf8ToIndex[value]?.let { return it }
            val encoded = value.toByteArray(Charsets.UTF_8)
            val assigned = nextCpIndex++
            extraCpBytes.write(CP_TAG_UTF8)
            extraCpBytes.write((encoded.size ushr 8) and 0xFF)
            extraCpBytes.write(encoded.size and 0xFF)
            extraCpBytes.write(encoded)
            utf8ToIndex[value] = assigned
            return assigned
        }

        val extraFieldsBytes = ByteArrayOutputStream()
        for (field in missingFields) {
            val nameIdx = getOrAddUtf8(field.name)
            val descIdx = getOrAddUtf8(field.descriptor)
            writeU2(extraFieldsBytes, field.accessFlags)
            writeU2(extraFieldsBytes, nameIdx)
            writeU2(extraFieldsBytes, descIdx)
            writeU2(extraFieldsBytes, 0)
        }

        val codeAttrNameIdx = if (missingMethods.isNotEmpty()) getOrAddUtf8("Code") else 0
        val extraMethodsBytes = ByteArrayOutputStream()
        for (method in missingMethods) {
            val nameIdx = getOrAddUtf8(method.name)
            val descIdx = getOrAddUtf8(method.descriptor)
            writeU2(extraMethodsBytes, method.accessFlags)
            writeU2(extraMethodsBytes, nameIdx)
            writeU2(extraMethodsBytes, descIdx)
            if ((method.accessFlags and ACC_ABSTRACT_OR_NATIVE) != 0) {
                writeU2(extraMethodsBytes, 0)
            } else {
                writeU2(extraMethodsBytes, 1)
                writeStubCodeAttribute(extraMethodsBytes, codeAttrNameIdx, method.accessFlags, method.descriptor)
            }
        }

        val out = ByteArrayOutputStream(curr.rawBytes.size + extraCpBytes.size() + 256)
        out.write(curr.rawBytes, 0, 8)
        writeU2(out, nextCpIndex)
        out.write(curr.rawBytes, curr.cp.startOffset, curr.cp.endOffset - curr.cp.startOffset)
        extraCpBytes.writeTo(out)
        out.write(curr.rawBytes, curr.cp.endOffset, curr.fields.startOffset - curr.cp.endOffset)
        writeU2(out, curr.fields.count + missingFields.size)
        val origFieldsBodyStart = curr.fields.startOffset + 2
        out.write(curr.rawBytes, origFieldsBodyStart, curr.fields.endOffset - origFieldsBodyStart)
        extraFieldsBytes.writeTo(out)
        writeU2(out, curr.methods.count + missingMethods.size)
        val origMethodsBodyStart = curr.fields.endOffset + 2
        out.write(curr.rawBytes, origMethodsBodyStart, curr.methods.endOffset - origMethodsBodyStart)
        extraMethodsBytes.writeTo(out)
        out.write(curr.rawBytes, curr.methods.endOffset, curr.rawBytes.size - curr.methods.endOffset)

        return out.toByteArray()
    }

    private fun writeStubCodeAttribute(
        out: ByteArrayOutputStream,
        codeAttrNameIdx: Int,
        accessFlags: Int,
        descriptor: String
    ) {
        val codeBytes = returnInstructionForDescriptor(descriptor)
        val maxLocals = computeMaxLocals(accessFlags, descriptor)
        val attrLen = 12 + codeBytes.size
        writeU2(out, codeAttrNameIdx)
        out.write((attrLen ushr 24) and 0xFF)
        out.write((attrLen ushr 16) and 0xFF)
        out.write((attrLen ushr 8) and 0xFF)
        out.write(attrLen and 0xFF)
        writeU2(out, 2)
        writeU2(out, maxLocals)
        out.write((codeBytes.size ushr 24) and 0xFF)
        out.write((codeBytes.size ushr 16) and 0xFF)
        out.write((codeBytes.size ushr 8) and 0xFF)
        out.write(codeBytes.size and 0xFF)
        out.write(codeBytes)
        writeU2(out, 0)
        writeU2(out, 0)
    }

    private fun returnInstructionForDescriptor(descriptor: String): ByteArray {
        val retTypeStart = descriptor.indexOf(')') + 1
        return when (descriptor.getOrNull(retTypeStart)) {
            'V', null -> byteArrayOf(OP_RETURN)
            'L', '[' -> byteArrayOf(OP_ACONST_NULL, OP_ARETURN)
            'J' -> byteArrayOf(OP_LCONST_0, OP_LRETURN)
            'F' -> byteArrayOf(OP_FCONST_0, OP_FRETURN)
            'D' -> byteArrayOf(OP_DCONST_0, OP_DRETURN)
            else -> byteArrayOf(OP_ICONST_0, OP_IRETURN)
        }
    }

    private fun computeMaxLocals(accessFlags: Int, descriptor: String): Int {
        var slots = if ((accessFlags and ACC_STATIC) == 0) 1 else 0
        var i = descriptor.indexOf('(') + 1
        val end = descriptor.indexOf(')')
        while (i in 1..<end) {
            when (descriptor[i]) {
                'L' -> {
                    val semi = descriptor.indexOf(';', i)
                    i = if (semi > i) semi + 1 else end
                    slots += 1
                }
                '[' -> {
                    while (i < end && descriptor[i] == '[') i++
                    if (i < end && descriptor[i] == 'L') {
                        val semi = descriptor.indexOf(';', i)
                        i = if (semi > i) semi + 1 else end
                    } else {
                        i++
                    }
                    slots += 1
                }
                'J', 'D' -> {
                    i++
                    slots += 2
                }
                else -> {
                    i++
                    slots += 1
                }
            }
        }
        return slots
    }

    private fun parseClass(bytes: ByteArray): ParsedClass? {
        if (bytes.size < MIN_CLASS_BYTES) return null
        val buf = ByteBuffer.wrap(bytes)
        if (buf.int != CLASS_MAGIC) return null
        buf.short
        buf.short
        val cpCount = buf.short.toInt() and U16_MASK
        val cpStartOffset = buf.position()
        val utf8Pool = HashMap<Int, String>()

        var slot = 1
        while (slot < cpCount) {
            val tag = buf.get().toInt() and 0xFF
            if (tag == CP_TAG_UTF8) {
                val len = buf.short.toInt() and U16_MASK
                val strBytes = ByteArray(len)
                buf.get(strBytes)
                utf8Pool[slot] = String(strBytes, Charsets.UTF_8)
                slot += 1
            } else {
                val skip = when (tag) {
                    CP_TAG_CLASS, CP_TAG_STRING, CP_TAG_METHOD_TYPE, CP_TAG_MODULE, CP_TAG_PACKAGE -> 2
                    CP_TAG_METHOD_HANDLE -> 3
                    CP_TAG_INTEGER, CP_TAG_FLOAT, CP_TAG_FIELDREF, CP_TAG_METHODREF,
                    CP_TAG_INTERFACE_METHODREF, CP_TAG_NAME_AND_TYPE, CP_TAG_DYNAMIC, CP_TAG_INVOKE_DYNAMIC -> 4
                    CP_TAG_LONG, CP_TAG_DOUBLE -> 8
                    else -> throw IllegalArgumentException("Unknown constant pool tag: $tag")
                }
                buf.position(buf.position() + skip)
                slot += if (tag == CP_TAG_LONG || tag == CP_TAG_DOUBLE) 2 else 1
            }
        }
        val cpEndOffset = buf.position()

        buf.position(buf.position() + 6)
        val interfacesCount = buf.short.toInt() and U16_MASK
        buf.position(buf.position() + interfacesCount * 2)

        fun readMembers(count: Int): List<MemberSignature> {
            val result = ArrayList<MemberSignature>(count)
            repeat(count) {
                val flagsOffset = buf.position()
                val accessFlags = buf.short.toInt() and U16_MASK
                val nameIdx = buf.short.toInt() and U16_MASK
                val descIdx = buf.short.toInt() and U16_MASK
                val attrsCount = buf.short.toInt() and U16_MASK
                repeat(attrsCount) {
                    buf.short
                    val attrLen = buf.int
                    buf.position(buf.position() + attrLen)
                }
                result.add(
                    MemberSignature(
                        accessFlags = accessFlags,
                        name = utf8Pool[nameIdx].orEmpty(),
                        descriptor = utf8Pool[descIdx].orEmpty(),
                        accessFlagsOffset = flagsOffset
                    )
                )
            }
            return result
        }

        val fieldsStartOffset = buf.position()
        val fieldsCount = buf.short.toInt() and U16_MASK
        val fields = readMembers(fieldsCount)
        val fieldsEndOffset = buf.position()

        val methodsStartOffset = buf.position()
        val methodsCount = buf.short.toInt() and U16_MASK
        val methods = readMembers(methodsCount)
        val methodsEndOffset = buf.position()

        return ParsedClass(
            rawBytes = bytes,
            cp = ConstantPoolSection(cpCount, cpStartOffset, cpEndOffset, utf8Pool),
            fields = MemberSection(fieldsStartOffset, fieldsCount, fieldsEndOffset, fields),
            methods = MemberSection(methodsStartOffset, methodsCount, methodsEndOffset, methods)
        )
    }

    private fun writeU2(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

@Suppress("MagicNumber")
private object DexMemberParser {
    private const val MIN_DEX_BYTES = 112
    private const val DEX_STRING_IDS_OFFSET = 56
    private const val DEX_TYPE_IDS_OFFSET = 64
    private const val DEX_PROTO_IDS_OFFSET = 72
    private const val DEX_FIELD_IDS_OFFSET = 80
    private const val DEX_METHOD_IDS_OFFSET = 88
    private const val DEX_CLASS_DEFS_OFFSET = 96
    private const val DEX_PROTO_ITEM_SIZE = 12
    private const val DEX_MEMBER_ID_ITEM_SIZE = 8
    private const val DEX_CLASS_DEF_ITEM_SIZE = 32
    private const val DEX_CLASS_DATA_FIELD_OFFSET = 24
    private const val U16_MASK = 0xFFFF

    fun parse(dexBytes: ByteArray, targetClassName: String? = null): AdditiveClassMerger.DexClassMembers? {
        val allClasses = parseAllClasses(dexBytes)
        if (allClasses.isEmpty()) return null
        if (targetClassName != null) {
            allClasses[targetClassName]?.let { return it }
        }
        return allClasses.entries
            .firstOrNull { !it.key.contains("\$\$ExternalSynthetic") }
            ?.value
            ?: allClasses.values.first()
    }

    fun parseAllClasses(dexBytes: ByteArray): Map<String, AdditiveClassMerger.DexClassMembers> {
        if (dexBytes.size < MIN_DEX_BYTES) return emptyMap()
        if (dexBytes[0] != 'd'.code.toByte() || dexBytes[1] != 'e'.code.toByte() || dexBytes[2] != 'x'.code.toByte()) {
            return emptyMap()
        }
        val buf = ByteBuffer.wrap(dexBytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsSize = buf.getInt(DEX_STRING_IDS_OFFSET)
        val stringIdsOff = buf.getInt(DEX_STRING_IDS_OFFSET + 4)
        val typeIdsSize = buf.getInt(DEX_TYPE_IDS_OFFSET)
        val typeIdsOff = buf.getInt(DEX_TYPE_IDS_OFFSET + 4)
        val protoIdsSize = buf.getInt(DEX_PROTO_IDS_OFFSET)
        val protoIdsOff = buf.getInt(DEX_PROTO_IDS_OFFSET + 4)
        val fieldIdsSize = buf.getInt(DEX_FIELD_IDS_OFFSET)
        val fieldIdsOff = buf.getInt(DEX_FIELD_IDS_OFFSET + 4)
        val methodIdsSize = buf.getInt(DEX_METHOD_IDS_OFFSET)
        val methodIdsOff = buf.getInt(DEX_METHOD_IDS_OFFSET + 4)
        val classDefsSize = buf.getInt(DEX_CLASS_DEFS_OFFSET)
        val classDefsOff = buf.getInt(DEX_CLASS_DEFS_OFFSET + 4)
        if (classDefsSize <= 0) return emptyMap()

        fun readDexString(idx: Int): String {
            if (idx !in 0..<stringIdsSize) return ""
            val strDataOff = buf.getInt(stringIdsOff + idx * 4)
            buf.position(strDataOff)
            readUleb128(buf)
            val start = buf.position()
            var end = start
            while (end < dexBytes.size && dexBytes[end] != 0.toByte()) end++
            return String(dexBytes, start, end - start, Charsets.UTF_8)
        }

        fun readDexType(typeIdx: Int): String {
            if (typeIdx !in 0..<typeIdsSize) return ""
            val strIdx = buf.getInt(typeIdsOff + typeIdx * 4)
            return readDexString(strIdx)
        }

        fun readDexProto(protoIdx: Int): String {
            if (protoIdx !in 0..<protoIdsSize) return "()V"
            val base = protoIdsOff + protoIdx * DEX_PROTO_ITEM_SIZE
            val returnTypeIdx = buf.getInt(base + 4)
            val paramsOff = buf.getInt(base + 8)
            val sb = StringBuilder("(")
            if (paramsOff != 0) {
                val count = buf.getInt(paramsOff)
                for (i in 0..<count) {
                    val tIdx = buf.getShort(paramsOff + 4 + i * 2).toInt() and U16_MASK
                    sb.append(readDexType(tIdx))
                }
            }
            sb.append(")").append(readDexType(returnTypeIdx))
            return sb.toString()
        }

        fun decodeFields(count: Int, outFields: MutableList<AdditiveClassMerger.MemberSignature>) {
            var fieldIdx = 0
            repeat(count) {
                fieldIdx += readUleb128(buf)
                val access = readUleb128(buf) and U16_MASK
                val pos = buf.position()
                if (fieldIdx in 0..<fieldIdsSize) {
                    val itemBase = fieldIdsOff + fieldIdx * DEX_MEMBER_ID_ITEM_SIZE
                    val typeIdx = buf.getShort(itemBase + 2).toInt() and U16_MASK
                    val nameIdx = buf.getInt(itemBase + 4)
                    val typeName = readDexType(typeIdx)
                    outFields.add(AdditiveClassMerger.MemberSignature(access, readDexString(nameIdx), typeName))
                }
                buf.position(pos)
            }
        }

        fun decodeMethods(count: Int, outMethods: MutableList<AdditiveClassMerger.MemberSignature>) {
            var methodIdx = 0
            repeat(count) {
                methodIdx += readUleb128(buf)
                val access = readUleb128(buf) and U16_MASK
                readUleb128(buf)
                val pos = buf.position()
                if (methodIdx in 0..<methodIdsSize) {
                    val itemBase = methodIdsOff + methodIdx * DEX_MEMBER_ID_ITEM_SIZE
                    val protoIdx = buf.getShort(itemBase + 2).toInt() and U16_MASK
                    val nameIdx = buf.getInt(itemBase + 4)
                    val proto = readDexProto(protoIdx)
                    outMethods.add(AdditiveClassMerger.MemberSignature(access, readDexString(nameIdx), proto))
                }
                buf.position(pos)
            }
        }

        val result = LinkedHashMap<String, AdditiveClassMerger.DexClassMembers>(classDefsSize)
        for (i in 0..<classDefsSize) {
            val itemOff = classDefsOff + i * DEX_CLASS_DEF_ITEM_SIZE
            val classTypeIdx = buf.getInt(itemOff)
            val classDescriptor = readDexType(classTypeIdx)
            val className = classDescriptor.removeSurrounding("L", ";").replace('/', '.')
            val superTypeIdx = buf.getInt(itemOff + 8)
            val superClassName = if (superTypeIdx in 0..<typeIdsSize) {
                readDexType(superTypeIdx).removeSurrounding("L", ";").takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val classDataOff = buf.getInt(itemOff + DEX_CLASS_DATA_FIELD_OFFSET)
            if (classDataOff == 0) {
                result[className] = AdditiveClassMerger.DexClassMembers(emptyList(), emptyList(), superClassName)
                continue
            }
            buf.position(classDataOff)
            val staticFieldsSize = readUleb128(buf)
            val instanceFieldsSize = readUleb128(buf)
            val directMethodsSize = readUleb128(buf)
            val virtualMethodsSize = readUleb128(buf)

            val fields = mutableListOf<AdditiveClassMerger.MemberSignature>()
            val methods = mutableListOf<AdditiveClassMerger.MemberSignature>()
            decodeFields(staticFieldsSize, fields)
            decodeFields(instanceFieldsSize, fields)
            decodeMethods(directMethodsSize, methods)
            decodeMethods(virtualMethodsSize, methods)
            result[className] = AdditiveClassMerger.DexClassMembers(fields, methods, superClassName)
        }

        return result
    }

    private fun readUleb128(buf: ByteBuffer): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf.get().toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result
    }
}
