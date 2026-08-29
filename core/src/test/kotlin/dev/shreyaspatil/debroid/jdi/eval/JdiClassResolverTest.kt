package dev.shreyaspatil.debroid.jdi.eval

import com.sun.jdi.ClassLoaderReference
import com.sun.jdi.ClassType
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JdiClassResolverTest {

    private lateinit var vm: VirtualMachine
    private lateinit var frame: StackFrame
    private lateinit var thread: ThreadReference
    private lateinit var resolver: JdiClassResolver

    @BeforeEach
    fun setUp() {
        thread = mockk {
            every { isSuspended } returns true
        }
        frame = mockk {
            every { thread() } returns thread
            every { location() } returns null
        }
        every { thread.frame(0) } returns frame
        vm = mockk {
            every { classesByName(any()) } returns emptyList()
            every { allClasses() } returns emptyList()
        }
        resolver = JdiClassResolver(vm, frame)
    }

    @Test
    fun `returns null for blank or empty class name`() {
        assertNull(resolver.resolveClass(""))
        assertNull(resolver.resolveClass("   "))
    }

    @Test
    fun `resolves loaded fully qualified class directly`() {
        val mathClass = mockk<ClassType> {
            every { name() } returns "java.lang.Math"
        }
        every { vm.classesByName("java.lang.Math") } returns listOf(mathClass)

        val result = resolver.resolveClass("java.lang.Math")
        assertEquals(mathClass, result)
    }

    @Test
    fun `resolves unqualified class from current frame package`() {
        val declaringClass = mockk<ClassType> {
            every { name() } returns "com.example.app.MainActivity"
        }
        val location = mockk<Location> {
            every { declaringType() } returns declaringClass
        }
        every { frame.location() } returns location

        val repoClass = mockk<ClassType> {
            every { name() } returns "com.example.app.DataRepository"
        }
        every { vm.classesByName("DataRepository") } returns emptyList()
        every { vm.classesByName("com.example.app.DataRepository") } returns listOf(repoClass)

        val result = resolver.resolveClass("DataRepository")
        assertEquals(repoClass, result)
    }

    @Test
    fun `resolves unqualified class from default import packages`() {
        val listClass = mockk<ClassType> {
            every { name() } returns "java.util.List"
        }
        every { vm.classesByName("List") } returns emptyList()
        every { vm.classesByName("java.util.List") } returns listOf(listClass)

        val result = resolver.resolveClass("List")
        assertEquals(listClass, result)
    }

    @Test
    fun `resolves nested class with dot notation to dollar notation`() {
        val innerClass = mockk<ClassType> {
            every { name() } returns "com.example.Outer\$Inner"
        }
        every { vm.classesByName("com.example.Outer.Inner") } returns emptyList()
        every { vm.classesByName("com.example.Outer\$Inner") } returns listOf(innerClass)

        val result = resolver.resolveClass("com.example.Outer.Inner")
        assertEquals(innerClass, result)
    }

    @Test
    fun `resolves Kotlin file facade with Kt suffix`() {
        val utilsKtClass = mockk<ClassType> {
            every { name() } returns "com.example.UtilsKt"
        }
        every { vm.classesByName("com.example.Utils") } returns emptyList()
        every { vm.classesByName("com.example.UtilsKt") } returns listOf(utilsKtClass)

        val result = resolver.resolveClass("com.example.Utils")
        assertEquals(utilsKtClass, result)
    }

    @Test
    fun `loads class on demand via ClassLoader when not yet loaded in VM`() {
        val classLoader = mockk<ClassLoaderReference>()
        val classLoaderType = mockk<ClassType>()
        val loadClassMethod = mockk<Method> {
            every { name() } returns "loadClass"
            every { argumentTypeNames() } returns listOf("java.lang.String")
        }
        val stringMirror = mockk<StringReference>()

        val declaringClass = mockk<ClassType> {
            every { name() } returns "com.example.App"
            every { classLoader() } returns classLoader
        }
        val location = mockk<Location> {
            every { declaringType() } returns declaringClass
        }
        every { frame.location() } returns location
        every { classLoader.referenceType() } returns classLoaderType
        every { classLoaderType.visibleMethods() } returns listOf(loadClassMethod)
        every { vm.mirrorOf("com.example.LazyClass") } returns stringMirror

        val loadedClass = mockk<ClassType> {
            every { name() } returns "com.example.LazyClass"
        }

        val classObj = mockk<com.sun.jdi.ClassObjectReference> {
            every { reflectedType() } returns loadedClass
        }

        every {
            classLoader.invokeMethod(
                thread,
                loadClassMethod,
                listOf(stringMirror),
                com.sun.jdi.ObjectReference.INVOKE_SINGLE_THREADED
            )
        } returns classObj

        every { vm.classesByName("com.example.LazyClass") } returnsMany listOf(
            emptyList(),
            listOf(loadedClass)
        )

        val result = resolver.resolveClass("com.example.LazyClass")
        assertEquals(loadedClass, result)
    }

    @Test
    fun `resolves class in package defining a static method via resolveClassWithStaticMethod`() {
        val mathKtClass = mockk<ClassType> {
            every { name() } returns "kotlin.math.MathKt"
        }
        val maxMethod = mockk<Method> {
            every { isStatic } returns true
        }
        every { mathKtClass.methodsByName("max") } returns listOf(maxMethod)
        every { vm.allClasses() } returns listOf(mathKtClass)

        val result = resolver.resolveClassWithStaticMethod("kotlin.math", "max")
        assertEquals(mathKtClass, result)
    }

    @Test
    fun `resolveClassWithStaticMethod ignores classes with non-static methods of same name`() {
        val nonStaticClass = mockk<ClassType> {
            every { name() } returns "com.example.InstanceHelper"
        }
        val instanceMethod = mockk<Method> {
            every { isStatic } returns false
        }
        every { nonStaticClass.methodsByName("doWork") } returns listOf(instanceMethod)
        every { vm.allClasses() } returns listOf(nonStaticClass)

        val result = resolver.resolveClassWithStaticMethod("com.example", "doWork")
        assertNull(result)
    }

    @Test
    fun `resolveClassWithStaticMethod returns null when package has no matching classes`() {
        every { vm.allClasses() } returns emptyList()

        val result = resolver.resolveClassWithStaticMethod("com.unknown.pkg", "foo")
        assertNull(result)
    }

    @Test
    fun `resolves unqualified nested inner class from current frame package`() {
        val declaringClass = mockk<ClassType> {
            every { name() } returns "com.example.service.OrderService"
        }
        val location = mockk<Location> {
            every { declaringType() } returns declaringClass
        }
        every { frame.location() } returns location

        val innerClass = mockk<ClassType> {
            every { name() } returns "com.example.service.OrderHolder\$Inner"
        }
        every { vm.classesByName("OrderHolder.Inner") } returns emptyList()
        every { vm.classesByName("OrderHolder\$Inner") } returns emptyList()
        every { vm.classesByName("com.example.service.OrderHolder.Inner") } returns emptyList()
        every { vm.classesByName("com.example.service.OrderHolder\$Inner") } returns listOf(innerClass)

        val result = resolver.resolveClass("OrderHolder.Inner")
        assertEquals(innerClass, result)
    }

    @Test
    fun `resolves targeted facade before scanning allClasses`() {
        val mathKtClass = mockk<ClassType> {
            every { name() } returns "kotlin.math.MathKt"
        }
        val staticMethod = mockk<Method> {
            every { isStatic } returns true
        }
        every { mathKtClass.methodsByName("max") } returns listOf(staticMethod)
        every { vm.classesByName("kotlin.math.MathKt") } returns listOf(mathKtClass)

        val result = resolver.resolveClassWithStaticMethod("kotlin.math", "max")
        assertEquals(mathKtClass, result)

        // Second call should return cached instance without additional lookup
        val cachedResult = resolver.resolveClassWithStaticMethod("kotlin.math", "max")
        assertEquals(mathKtClass, cachedResult)
    }
}
