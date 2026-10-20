package nl.lamia.most.exchange.fixadapter.session

import java.io.File
import java.util.HexFormat
import java.util.Properties

/** Which backend's library owns a session (Design.md §2). */
enum class Role(val key: String) {
    ORDER_ENTRY("order-entry"),
    MARKET_DATA("market-data"),
    ;

    companion object {
        fun of(key: String): Role? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One configured FIX session (Design.md §2). [senderCompId] is the adapter's CompID and
 * [targetCompId] the client's, as the adapter sends them. Only the SHA-256 of the password is kept.
 */
class ConfiguredSession(
    val name: String,
    val senderCompId: String,
    val targetCompId: String,
    val username: String,
    val passwordSha256: ByteArray,
    val role: Role,
    val participantId: Long?,
) {
    override fun toString() = "$name ($senderCompId/$targetCompId, ${role.key}" + (participantId?.let { ", participant $it" } ?: "") + ")"
}

/** The sessions file: every session the adapter lets in (Design.md §2, "Configuration and logon"). */
class ConfiguredSessions(val all: List<ConfiguredSession>) {
    private val byCompIds = all.associateBy { it.senderCompId to it.targetCompId }

    /** The session with these CompIDs, as the adapter sends them, or null. */
    fun find(senderCompId: String, targetCompId: String): ConfiguredSession? = byCompIds[senderCompId to targetCompId]

    companion object {
        private const val PREFIX = "session."
        private val HEX = HexFormat.of()

        fun load(file: File): ConfiguredSessions {
            require(file.isFile) { "sessions file not found: $file" }
            return parse(Properties().apply { file.inputStream().use(::load) }, file.path)
        }

        /** Refuses, naming the key, anything Design §2 does not allow; [source] names the file in the message. */
        fun parse(properties: Properties, source: String): ConfiguredSessions {
            val names = properties.stringPropertyNames().filter { it.startsWith(PREFIX) }
                .map { it.removePrefix(PREFIX).substringBefore('.') }.toSortedSet()
            require(names.isNotEmpty()) { "$source: no session is configured (no $PREFIX<name>.* keys)" }

            val sessions = names.map { name ->
                fun key(field: String) = "$PREFIX$name.$field"
                fun required(field: String): String =
                    properties.getProperty(key(field))?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("$source: missing ${key(field)}")

                val digest = required("passwordSha256")
                require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    "$source: ${key("passwordSha256")} must be 64 hex digits, the SHA-256 of the session's password"
                }
                val roleKey = required("role")
                val role = Role.of(roleKey)
                    ?: throw IllegalArgumentException("$source: ${key("role")}=$roleKey, not one of ${Role.entries.map { it.key }}")
                val participant = properties.getProperty(key("participantId"))?.trim()
                val participantId = when (role) {
                    Role.ORDER_ENTRY -> participant?.toLongOrNull()
                        ?: throw IllegalArgumentException("$source: ${key("participantId")} is required for order-entry, a number")
                    Role.MARKET_DATA -> {
                        require(participant == null) { "$source: ${key("participantId")} is for order-entry sessions only" }
                        null
                    }
                }
                ConfiguredSession(
                    name, required("senderCompId"), required("targetCompId"), required("username"),
                    HEX.parseHex(digest.lowercase()), role, participantId,
                )
            }

            sessions.groupBy { it.senderCompId to it.targetCompId }.values.firstOrNull { it.size > 1 }?.let { same ->
                throw IllegalArgumentException("$source: sessions ${same.map { it.name }} share CompIDs ${same[0].senderCompId}/${same[0].targetCompId}")
            }
            sessions.filter { it.role == Role.ORDER_ENTRY }.groupBy { it.participantId }.values.firstOrNull { it.size > 1 }?.let { same ->
                throw IllegalArgumentException(
                    "$source: sessions ${same.map { it.name }} share participantId ${same[0].participantId}; " +
                        "reports route back to one session per participant",
                )
            }
            return ConfiguredSessions(sessions)
        }
    }
}
