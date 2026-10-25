package nl.lamia.most.exchange.fixadapter.session

import io.aeron.CommonContext
import java.io.File
import java.util.Properties

/** The node file's `fix.*` keys (Design.md §2, "Configuration and logon"). */
class FixNodeConfig(
    val bindAddress: String,
    val port: Int,
    val logDir: File,
    val archiveDir: File,
    val aeronDir: String,
    val fileSyncLevel: Int,
    val sessionsFile: File,
) {
    companion object {
        const val BIND_ADDRESS = "fix.bindAddress"
        const val PORT = "fix.port"
        const val LOG_DIR = "fix.logDir"
        const val ARCHIVE_DIR = "fix.archiveDir"
        const val AERON_DIR = "fix.aeronDir"
        const val FILE_SYNC_LEVEL = "fix.fileSyncLevel"
        const val SESSIONS_FILE = "fix.sessionsFile"

        /** Loads [path] if given, then lets `fix.*` system properties override individual keys. */
        fun load(path: String?): FixNodeConfig = parse(loadProperties(path))

        /** The node file's keys as [load] reads them, for a backend's own (`fix.storeDir`, say). */
        fun loadProperties(path: String?): Properties {
            val properties = Properties()
            if (path != null) {
                val file = File(path)
                require(file.isFile) { "config file not found: $path" }
                file.inputStream().use(properties::load)
            }
            for ((key, value) in System.getProperties()) {
                val name = key as String
                if (name.startsWith("fix.")) properties.setProperty(name, value as String)
            }
            return properties
        }

        fun parse(properties: Properties): FixNodeConfig {
            fun required(key: String): String =
                properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalArgumentException("missing required configuration key: $key")

            val port = required(PORT).toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("$PORT must be a port number")
            val fileSyncLevel = properties.getProperty(FILE_SYNC_LEVEL)?.trim()?.let { level ->
                level.toIntOrNull()?.takeIf { it in 0..2 } ?: throw IllegalArgumentException("$FILE_SYNC_LEVEL must be 0, 1 or 2")
            } ?: 1
            return FixNodeConfig(
                bindAddress = properties.getProperty(BIND_ADDRESS)?.trim() ?: "0.0.0.0",
                port = port,
                logDir = File(required(LOG_DIR)),
                archiveDir = File(required(ARCHIVE_DIR)),
                aeronDir = properties.getProperty(AERON_DIR)?.trim() ?: (CommonContext.getAeronDirectoryName() + "-most-fix"),
                fileSyncLevel = fileSyncLevel,
                sessionsFile = File(required(SESSIONS_FILE)),
            )
        }
    }
}
