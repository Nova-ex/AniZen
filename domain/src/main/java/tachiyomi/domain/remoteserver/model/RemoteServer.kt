package tachiyomi.domain.remoteserver.model

// ANZ -->
data class RemoteServer(
    val id: Long = 0L,
    val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val keyPassphrase: String = "",
    val initialPath: String = "/",
    val passiveMode: Boolean = true,
    val ftpsImplicit: Boolean = false,
    val trustSelfSigned: Boolean = false,
    val createdAt: Long = 0L,
) {
    fun normalizedInitialPath(): String {
        val trimmed = initialPath.trim()
        if (trimmed.isEmpty()) return "/"
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }
}
// ANZ <--
