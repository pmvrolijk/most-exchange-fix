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

        // Market data's own engine (Design.md §2, §4).
        const val MD_BIND_ADDRESS = "fix.md.bindAddress"
        const val MD_PORT = "fix.md.port"
        const val MD_LOG_DIR = "fix.md.logDir"
        const val MD_ARCHIVE_DIR = "fix.md.archiveDir"
        const val MD_AERON_DIR = "fix.md.aeronDir"
        const val MD_FILE_SYNC_LEVEL = "fix.md.fileSyncLevel"

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

        private fun Properties.required(key: String): String =
            getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("missing required configuration key: $key")

        private fun Properties.port(key: String): Int =
            required(key).toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("$key must be a port number")

        private fun Properties.fileSyncLevel(key: String, default: Int): Int = getProperty(key)?.trim()?.let { level ->
            level.toIntOrNull()?.takeIf { it in 0..2 } ?: throw IllegalArgumentException("$key must be 0, 1 or 2")
        } ?: default

        /**
         * Market data's own engine (Design.md §4): its port and directories from the `fix.md.*` keys,
         * its archive unsynced unless told, the bind address and sessions file order entry's. None of
         * its port and directories may be order entry's.
         */
        fun parseMarketData(properties: Properties): FixNodeConfig {
            val orderEntry = parse(properties)
            val config = FixNodeConfig(
                bindAddress = properties.getProperty(MD_BIND_ADDRESS)?.trim()?.takeIf { it.isNotEmpty() } ?: orderEntry.bindAddress,
                port = properties.port(MD_PORT),
                logDir = File(properties.required(MD_LOG_DIR)),
                archiveDir = File(properties.required(MD_ARCHIVE_DIR)),
                aeronDir = properties.getProperty(MD_AERON_DIR)?.trim()?.takeIf { it.isNotEmpty() } ?: (orderEntry.aeronDir + "-md"),
                fileSyncLevel = properties.fileSyncLevel(MD_FILE_SYNC_LEVEL, 0),
                sessionsFile = orderEntry.sessionsFile,
            )
            require(config.port != orderEntry.port) { "$MD_PORT must not be $PORT: market data has its own engine" }
            for ((key, mine, theirs) in listOf(
                Triple(MD_LOG_DIR, config.logDir.absoluteFile, orderEntry.logDir.absoluteFile),
                Triple(MD_ARCHIVE_DIR, config.archiveDir.absoluteFile, orderEntry.archiveDir.absoluteFile),
                Triple(MD_AERON_DIR, File(config.aeronDir).absoluteFile, File(orderEntry.aeronDir).absoluteFile),
            )) {
                require(mine != theirs) { "$key must not be order entry's directory: market data's is emptied at every start" }
            }
            return config
        }

        fun parse(properties: Properties): FixNodeConfig {
            fun required(key: String): String = properties.required(key)
            val port = properties.port(PORT)
            val fileSyncLevel = properties.fileSyncLevel(FILE_SYNC_LEVEL, 1)
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
