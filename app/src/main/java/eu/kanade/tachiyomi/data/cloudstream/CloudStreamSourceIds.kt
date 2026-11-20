// ANZ -->
package eu.kanade.tachiyomi.data.cloudstream

import java.security.MessageDigest
import java.util.Locale

const val CLOUDSTREAM_SOURCE_ID_OFFSET: Long = 9_200_000_000_000L
const val CLOUDSTREAM_SOURCE_ID_LIMIT: Long = 9_300_000_000_000L
private const val CLOUDSTREAM_SOURCE_ID_SPAN: Long = 99_999_999_999L
private const val HASH_BYTE_MASK = 0xFFL
private const val HASH_SHIFT_BITS = 8
private const val HASH_BYTES_TO_READ = 7
private const val POSITIVE_MASK = 0x7FFF_FFFF_FFFF_FFFFL

fun computeCloudStreamSourceId(providerName: String, lang: String): Long {
    val key = "cloudstream|${providerName.trim().lowercase(Locale.ROOT)}|${lang.trim().lowercase(Locale.ROOT)}"
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    var acc = 0L
    for (i in 0 until HASH_BYTES_TO_READ) {
        acc = (acc shl HASH_SHIFT_BITS) or (digest[i].toLong() and HASH_BYTE_MASK)
    }
    val offset = ((acc and POSITIVE_MASK) % CLOUDSTREAM_SOURCE_ID_SPAN) + 1L
    return CLOUDSTREAM_SOURCE_ID_OFFSET + offset
}

fun Long.isCloudStreamSourceId(): Boolean = this in (CLOUDSTREAM_SOURCE_ID_OFFSET + 1L) until CLOUDSTREAM_SOURCE_ID_LIMIT
// ANZ <--
