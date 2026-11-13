package dev.shreyaspatil.debroid.hotreload

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object HotReloadSyncCoordinator {
    private const val CONNECT_TIMEOUT_MS = 250
    private const val READ_TIMEOUT_MS = 180_000
    private const val HASH_PREFIX_BYTES = 8
    private const val MAX_TCP_PORT = 65_535
    private const val SERVER_BACKLOG = 8
    private const val SYNC_COMMAND = "SYNC"

    internal fun defaultRegistryDir(): File {
        val userHome = System.getProperty("user.home").orEmpty()
        val base = if (userHome.isNotEmpty()) File(userHome, ".debroid") else File(System.getProperty("java.io.tmpdir"))
        return File(base, "hotreload")
    }

    internal fun portFileFor(
        appId: String,
        projectDir: File,
        registryDir: File = defaultRegistryDir()
    ): File {
        val canonicalProject = runCatching { projectDir.canonicalPath }.getOrElse { projectDir.absolutePath }
        val key = "$appId|$canonicalProject"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .take(HASH_PREFIX_BYTES)
            .joinToString("") { "%02x".format(Locale.US, it) }
        val safeApp = appId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(registryDir, "$safeApp-$hash.port")
    }

    fun trySyncWithRunningWatcher(
        config: HotReloadConfig,
        registryDir: File = defaultRegistryDir(),
        onLine: (String) -> Unit
    ): Boolean {
        val portFile = portFileFor(config.appId, config.projectDir, registryDir)
        if (!portFile.isFile) return false

        val port = runCatching { portFile.readText().trim().toInt() }.getOrNull()
        if (port == null || port !in 1..MAX_TCP_PORT) {
            runCatching { portFile.delete() }
            return false
        }

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                writer.write("$SYNC_COMMAND\n")
                writer.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotEmpty()) {
                        onLine(line)
                    }
                    line = reader.readLine()
                }
            }
            true
        } catch (_: Exception) {
            runCatching { portFile.delete() }
            false
        }
    }

    internal class Server private constructor(
        private val serverSocket: ServerSocket,
        private val portFile: File,
        private val onSyncRequest: () -> List<String>
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        fun start() {
            Thread {
                while (!closed.get()) {
                    val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    Thread {
                        handleClient(client)
                    }.apply {
                        name = "debroid-hr-sync-client"
                        isDaemon = true
                        start()
                    }
                }
            }.apply {
                name = "debroid-hr-sync-accept"
                isDaemon = true
                start()
            }
        }

        private fun handleClient(client: Socket) {
            runCatching {
                client.use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                    val cmd = reader.readLine()?.trim().orEmpty()
                    if (cmd == SYNC_COMMAND) {
                        val lines = onSyncRequest()
                        val writer = OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)
                        for (line in lines) {
                            writer.write(line)
                            writer.write("\n")
                        }
                        writer.flush()
                    }
                }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { portFile.delete() }
            runCatching { serverSocket.close() }
        }

        companion object {
            fun startOrNull(
                appId: String,
                projectDir: File,
                registryDir: File = defaultRegistryDir(),
                onSyncRequest: () -> List<String>
            ): Server? = runCatching {
                registryDir.mkdirs()
                val serverSocket = ServerSocket(0, SERVER_BACKLOG, InetAddress.getLoopbackAddress())
                val portFile = portFileFor(appId, projectDir, registryDir)
                portFile.writeText(serverSocket.localPort.toString())
                Server(serverSocket, portFile, onSyncRequest).also { it.start() }
            }.getOrNull()
        }
    }
}
