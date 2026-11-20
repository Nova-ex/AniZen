package tachiyomi.domain.remoteserver.model

// ANZ -->
enum class RemoteProtocol(val displayName: String, val defaultPort: Int) {
    FTP("FTP", 21),
    FTPS("FTPS", 21),
    SFTP("SFTP", 22),
    ;

    companion object {
        fun fromString(value: String): RemoteProtocol {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FTP
        }
    }
}
// ANZ <--
