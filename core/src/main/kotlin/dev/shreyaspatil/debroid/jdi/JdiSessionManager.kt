package dev.shreyaspatil.debroid.jdi

import com.sun.jdi.Bootstrap
import com.sun.jdi.VirtualMachine
import dev.shreyaspatil.debroid.adb.AdbManager
import dev.shreyaspatil.debroid.adb.DebugException
import dev.shreyaspatil.debroid.models.ErrorCode
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

open class JdiSessionManager(
    private val adbManager: AdbManager = AdbManager(),
    private val jdiConnector: JdiConnector = DefaultJdiConnector()
) {
    private val sessions = ConcurrentHashMap<String, JdiSession>()
    private val sessionCounter = AtomicInteger(100)

    fun launchAndAttach(appId: String, suspend: Boolean = true): JdiSession {
        sweepDeadSessions()
        // 1. Check debuggability
        val debuggableRes = adbManager.isAppDebuggable(appId)
        if (debuggableRes.isFailure) {
            throw debuggableRes.exceptionOrNull()!!
        }
        if (!debuggableRes.getOrThrow()) {
            throw DebugException(
                ErrorCode.APP_NOT_DEBUGGABLE,
                "Application $appId is not debuggable (android:debuggable=\"false\")."
            )
        }

        // 2. Launch app in suspended state waiting for debugger
        val pidRes = adbManager.launchAppSuspended(appId)
        val pid = pidRes.getOrThrow()

        // 3. Attach JDI
        return attachToPid(appId, pid, clearDebugAppOnDetach = true, initialSuspend = suspend)
    }

    fun attachToRunningApp(appId: String, suspend: Boolean = false): JdiSession {
        sweepDeadSessions()
        // 1. Check debuggability
        val debuggableRes = adbManager.isAppDebuggable(appId)
        if (debuggableRes.isFailure) {
            throw debuggableRes.exceptionOrNull()!!
        }
        if (!debuggableRes.getOrThrow()) {
            throw DebugException(ErrorCode.APP_NOT_DEBUGGABLE, "Application $appId is not debuggable.")
        }

        // 2. Find PID
        val pid = adbManager.findPid(appId).getOrThrow()

        // 3. Attach JDI
        return attachToPid(appId, pid, clearDebugAppOnDetach = false, initialSuspend = suspend)
    }

    private fun attachToPid(
        appId: String,
        pid: Int,
        clearDebugAppOnDetach: Boolean,
        initialSuspend: Boolean = false
    ): JdiSession {
        // Ensure only one active debug session per app: detach any existing
        val existingSessionIds = sessions.values.filter { it.appId == appId }.map { it.sessionId }
        for (id in existingSessionIds) {
            detachSession(id)
        }

        val port = findAvailableLocalPort()
        adbManager.forwardJdwpPort(port, pid).getOrThrow()

        val sessionId = "sess_${sessionCounter.getAndIncrement()}"

        // Retry connection up to 10 times (JDWP port forward might take a split second)
        var vm: VirtualMachine? = null
        var lastException: Exception? = null
        var attempts = 0

        while (vm == null && attempts < 10) {
            attempts++
            try {
                vm = jdiConnector.attach("localhost", port)
            } catch (e: Exception) {
                lastException = e
                Thread.sleep(300)
            }
        }

        if (vm == null) {
            adbManager.removePortForward(port)
            throw DebugException(
                ErrorCode.ADB_ERROR,
                "Failed to attach JDI to localhost:$port for $appId (pid $pid): ${lastException?.message}"
            )
        }

        if (initialSuspend) {
            try {
                vm.suspend()
            } catch (e: Exception) {
                adbManager.removePortForward(port)
                throw DebugException(
                    ErrorCode.INTERNAL_ERROR,
                    "Failed to suspend VM for $appId: ${e.message}"
                )
            }
        }

        val session =
            JdiSession(
                sessionId = sessionId,
                appId = appId,
                localPort = port,
                vm = vm,
                adbManager = adbManager,
                clearDebugAppOnDetach = clearDebugAppOnDetach
            )
        sessions[sessionId] = session
        return session
    }

    fun getSession(sessionId: String): JdiSession {
        val session = sessions[sessionId]
            ?: throw DebugException(ErrorCode.SESSION_NOT_FOUND, "Session $sessionId not found.")
        if (!session.isAlive()) {
            detachSession(sessionId)
            throw DebugException(ErrorCode.SESSION_NOT_FOUND, "Session $sessionId has disconnected.")
        }
        return session
    }

    private fun sweepDeadSessions() {
        val deadIds = sessions.filterValues { !it.isAlive() }.keys.toList()
        for (id in deadIds) {
            detachSession(id)
        }
    }

    fun detachSession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        session.detach()
        return true
    }

    fun detachAllSessions() {
        val activeIds = sessions.keys.toList()
        for (id in activeIds) {
            detachSession(id)
        }
    }

    private fun findAvailableLocalPort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }
}

interface JdiConnector {
    fun attach(host: String, port: Int): VirtualMachine
}

class DefaultJdiConnector : JdiConnector {
    override fun attach(host: String, port: Int): VirtualMachine {
        val vmm = Bootstrap.virtualMachineManager()
        val socketConnector = vmm.attachingConnectors()
            .firstOrNull { it.name() == "com.sun.jdi.SocketAttach" }
            ?: throw DebugException(
                ErrorCode.INTERNAL_ERROR,
                "SocketAttach connector not found in JDI VirtualMachineManager"
            )

        val arguments = socketConnector.defaultArguments()
        arguments["hostname"]?.setValue(host)
        arguments["port"]?.setValue(port.toString())

        return socketConnector.attach(arguments)
    }
}
