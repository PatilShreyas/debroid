package dev.shreyaspatil.debroid.jdi.eval

import com.sun.jdi.ClassLoaderReference
import com.sun.jdi.ClassObjectReference
import com.sun.jdi.ClassType
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.VirtualMachine

/**
 * Resolves [ReferenceType] mirrors in the target [VirtualMachine] for static method
 * and field/constant evaluation.
 *
 * Supports:
 * 1. Fully-qualified class names (e.g., `java.lang.Math`, `android.util.Log`).
 * 2. Unqualified class names within the current frame's package.
 * 3. Standard default package imports (`java.lang.*`, `java.util.*`, `kotlin.*`, `android.util.*`, etc.).
 * 4. Nested / inner class resolution using JVM binary naming conventions (`Outer$Inner`).
 * 5. On-demand class loading via the active thread's [ClassLoaderReference].
 */
class JdiClassResolver(
    private val vm: VirtualMachine,
    private val initialFrame: StackFrame
) {
    // Cache the ThreadReference once at creation time. In JDI on Android ART, executing a method invocation
    // on the debuggee VM momentarily resumes execution and invalidates previous StackFrame objects. Calling
    // frame.thread() subsequently throws IncompatibleThreadStateException ("Thread has been resumed").
    // We dynamically query thread.frame(0) via activeFrame to always use a valid stack frame.
    private val thread = runCatching { initialFrame.thread() }.getOrNull()
    private val activeFrame: StackFrame get() = runCatching { thread?.frame(0) }.getOrNull() ?: initialFrame

    /**
     * Resolves a [ReferenceType] matching [className] in the VM.
     *
     * @param className Fully qualified or simple class name (e.g. `java.lang.Math` or `Math`).
     * @return The resolved [ReferenceType], or `null` if not found.
     */
    fun resolveClass(className: String): ReferenceType? {
        val trimmed = className.trim()
        if (trimmed.isEmpty()) return null

        val loaded = findLoadedClassOrUnqualified(trimmed)
        if (loaded != null) return loaded

        // Try Kotlin file facade suffix (*Kt) if not already explicitly suffixed
        if (!trimmed.endsWith("Kt")) {
            val loadedKt = findLoadedClassOrUnqualified("${trimmed}Kt")
            if (loadedKt != null) return loadedKt
        }

        return tryLoadOnDemandWithKtFallback(trimmed)
    }

    private val staticMethodClassCache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, ReferenceType>()

    /**
     * Resolves a [ReferenceType] matching [pkgPrefix] or starting with [pkgPrefix] that defines
     * a static method named [methodName]. Useful for resolving Kotlin package-level file facades (e.g. `MathKt`).
     */
    fun resolveClassWithStaticMethod(pkgPrefix: String, methodName: String): ReferenceType? {
        val cacheKey = pkgPrefix to methodName
        val cached = staticMethodClassCache[cacheKey]
        if (cached != null) return cached

        val resolved = findClassWithStaticMethod(pkgPrefix, methodName)
        if (resolved != null) {
            staticMethodClassCache[cacheKey] = resolved
        }
        return resolved
    }

    private fun findClassWithStaticMethod(pkgPrefix: String, methodName: String): ReferenceType? {
        val loaded = findLoadedClassOrUnqualified(pkgPrefix)
        if (loaded != null && hasStaticMethod(loaded, methodName)) {
            return loaded
        }

        // Check common Kotlin file facade conventions first (e.g. "kotlin.math.MathKt", "DataRepositoryKt")
        // to avoid expensive vm.allClasses() linear scans and JDWP ADB packet round-trips.
        val sourceFileFacade = runCatching {
            val srcName = activeFrame.location()?.sourceName()
            val baseName = srcName?.removeSuffix(".kt")?.removeSuffix(".java")
            baseName?.let { "$pkgPrefix.${it}Kt" }
        }.getOrNull()

        val targetedFacades = listOfNotNull(
            sourceFileFacade,
            "${pkgPrefix}Kt",
            "$pkgPrefix.${methodName.replaceFirstChar { it.uppercase() }}Kt",
            "$pkgPrefix.${pkgPrefix.substringAfterLast('.').replaceFirstChar { it.uppercase() }}Kt"
        )
        for (facade in targetedFacades) {
            val targeted = resolveClass(facade)
            if (targeted != null && hasStaticMethod(targeted, methodName)) {
                return targeted
            }
        }

        // Fallback: Scan loaded classes within the package prefix
        return runCatching {
            vm.allClasses().find { refType ->
                matchesPrefix(refType.name(), pkgPrefix) && hasStaticMethod(refType, methodName)
            }
        }.getOrNull()
    }

    private fun hasStaticMethod(refType: ReferenceType, methodName: String): Boolean {
        return runCatching {
            refType.methodsByName(methodName).any { it.isStatic }
        }.getOrDefault(false)
    }

    private fun matchesPrefix(name: String, prefix: String): Boolean {
        return name == prefix || name.startsWith("$prefix.") || name.startsWith("$prefix$")
    }

    private fun findLoadedClassOrUnqualified(name: String): ReferenceType? {
        val loadedDirect = findLoadedClass(name)
        if (loadedDirect != null) return loadedDirect

        // Unqualified lookup (single identifier without package dots)
        if (!name.contains('.')) {
            return resolveUnqualifiedClass(name)
        }

        // If name contains a dot (e.g. Outer.Inner), check if Outer is in the current package or default imports
        val currentPkg = getCurrentPackage()
        if (currentPkg != null) {
            val fromPkg = findLoadedClass("$currentPkg.$name")
            if (fromPkg != null) return fromPkg
        }

        for (pkg in defaultImportPackages) {
            val fromDefault = findLoadedClass("$pkg.$name")
            if (fromDefault != null) return fromDefault
        }

        return null
    }

    private fun tryLoadOnDemandWithKtFallback(name: String): ReferenceType? {
        val onDemand = tryLoadClassOnDemand(name)
        if (onDemand != null) return onDemand

        if (!name.endsWith("Kt")) {
            return tryLoadClassOnDemand("${name}Kt")
        }
        return null
    }

    private fun resolveUnqualifiedClass(simpleName: String): ReferenceType? {
        val currentPkg = getCurrentPackage()
        if (currentPkg != null) {
            val fromPkg = findLoadedClass("$currentPkg.$simpleName")
            if (fromPkg != null) return fromPkg
        }

        val fromDefaultPkg = findInDefaultPackages(simpleName)
        if (fromDefaultPkg != null) return fromDefaultPkg

        return findLoadedClassBySimpleName(simpleName)
    }

    private fun findInDefaultPackages(simpleName: String): ReferenceType? {
        for (pkg in defaultImportPackages) {
            val candidate = findLoadedClass("$pkg.$simpleName")
            if (candidate != null) return candidate
        }
        return null
    }

    /**
     * Attempts to find an already loaded class in the VM, trying both standard dot notation
     * and inner class `$` notation.
     */
    private fun findLoadedClass(name: String): ReferenceType? {
        val direct = vm.classesByName(name).firstOrNull()
        if (direct != null) return direct

        if (name.contains('.')) {
            return findInnerClass(name)
        }

        return null
    }

    private fun findInnerClass(name: String): ReferenceType? {
        var candidate = name
        // Progressively replace dots with dollar signs from right-to-left to match JVM binary inner class names
        while (candidate.contains('.')) {
            candidate = candidate.replaceLast('.', '$')
            val inner = vm.classesByName(candidate).firstOrNull()
            if (inner != null) return inner
        }
        return null
    }

    /**
     * Searches `vm.allClasses()` for a loaded class whose simple name matches [simpleName].
     */
    private fun findLoadedClassBySimpleName(simpleName: String): ReferenceType? {
        val candidates = getMatchingClassCandidates(simpleName)
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()

        return findBestMatchingCandidate(candidates, simpleName)
    }

    private fun getMatchingClassCandidates(simpleName: String): List<ReferenceType> {
        return runCatching {
            vm.allClasses().filter { refType ->
                val name = refType.name()
                name == simpleName ||
                    name.endsWith(".$simpleName") ||
                    name.endsWith("\$$simpleName")
            }
        }.getOrDefault(emptyList())
    }

    private fun findBestMatchingCandidate(
        candidates: List<ReferenceType>,
        simpleName: String
    ): ReferenceType {
        val currentPkg = getCurrentPackage()
        if (currentPkg != null) {
            val pkgMatch = candidates.find { it.name() == "$currentPkg.$simpleName" }
            if (pkgMatch != null) return pkgMatch
        }

        for (pkg in defaultImportPackages) {
            val defaultMatch = candidates.find { it.name() == "$pkg.$simpleName" }
            if (defaultMatch != null) return defaultMatch
        }

        return candidates.first()
    }

    /**
     * Attempts on-demand class loading via `Class.forName(name, true, classLoader)` or `ClassLoader.loadClass(name)`
     * on the frame's class loader. Using `Class.forName(..., initialize=true, ...)` initializes static singletons
     * and companion objects.
     */
    private fun tryLoadClassOnDemand(name: String): ReferenceType? {
        val declaringType = runCatching { activeFrame.location()?.declaringType() }.getOrNull() ?: return null
        val classLoader = runCatching { declaringType.classLoader() }.getOrNull() ?: return null

        val candidates = buildOnDemandCandidates(name)
        for (candidate in candidates) {
            val loaded = invokeForNameOrLoadClass(classLoader, candidate)
            if (loaded != null) return loaded
        }
        return null
    }

    private fun buildOnDemandCandidates(name: String): List<String> {
        val list = mutableListOf(name)
        if (name.contains('.')) {
            var innerCandidate = name
            while (innerCandidate.contains('.')) {
                innerCandidate = innerCandidate.replaceLast('.', '$')
                list.add(innerCandidate)
            }
        }
        val currentPkg = getCurrentPackage()
        if (currentPkg != null && !name.startsWith("$currentPkg.")) {
            list.add("$currentPkg.$name")
            if (name.contains('.')) {
                var innerCandidate = "$currentPkg.$name"
                while (innerCandidate.contains('.')) {
                    innerCandidate = innerCandidate.replaceLast('.', '$')
                    list.add(innerCandidate)
                }
            }
        }
        return list
    }

    private fun invokeForNameOrLoadClass(
        classLoader: ClassLoaderReference,
        candidateName: String
    ): ReferenceType? {
        val forNameLoaded = runCatching {
            val javaLangClass = vm.classesByName("java.lang.Class").firstOrNull() as? ClassType
            val forNameMethod = javaLangClass?.methodsByName("forName")
                ?.find { it.argumentTypeNames() == listOf("java.lang.String", "boolean", "java.lang.ClassLoader") }
            if (javaLangClass != null && forNameMethod != null) {
                val stringMirror = vm.mirrorOf(candidateName)
                val boolMirror = vm.mirrorOf(true)
                val classObj = javaLangClass.invokeMethod(
                    thread,
                    forNameMethod,
                    listOf(stringMirror, boolMirror, classLoader),
                    ClassType.INVOKE_SINGLE_THREADED
                )
                if (classObj is ClassObjectReference) {
                    runCatching { classObj.reflectedType() }.getOrNull()
                        ?: vm.classesByName(candidateName).firstOrNull()
                } else {
                    null
                }
            } else {
                null
            }
        }.getOrNull()

        if (forNameLoaded != null) return forNameLoaded

        return runCatching {
            val loadClassMethod = classLoader.referenceType()
                .visibleMethods()
                .find { it.name() == "loadClass" && it.argumentTypeNames() == listOf("java.lang.String") }
                ?: return@runCatching null

            val stringMirror = vm.mirrorOf(candidateName)
            val classObj = classLoader.invokeMethod(
                thread,
                loadClassMethod,
                listOf(stringMirror),
                ObjectReference.INVOKE_SINGLE_THREADED
            )
            if (classObj is ClassObjectReference) {
                runCatching { classObj.reflectedType() }.getOrNull()
                    ?: vm.classesByName(candidateName).firstOrNull()
            } else {
                vm.classesByName(candidateName).firstOrNull()
            }
        }.getOrNull()
    }

    private fun getCurrentPackage(): String? {
        val declaringName = runCatching { activeFrame.location()?.declaringType()?.name() }.getOrNull() ?: return null
        return if (declaringName.contains('.')) declaringName.substringBeforeLast('.') else null
    }

    private fun String.replaceLast(oldChar: Char, newChar: Char): String {
        val index = lastIndexOf(oldChar)
        return if (index < 0) this else substring(0, index) + newChar + substring(index + 1)
    }

    companion object {
        private val defaultImportPackages = listOf(
            "java.lang",
            "java.util",
            "kotlin",
            "kotlin.math",
            "kotlin.collections",
            "android.util",
            "android.os",
            "android.view"
        )
    }
}
