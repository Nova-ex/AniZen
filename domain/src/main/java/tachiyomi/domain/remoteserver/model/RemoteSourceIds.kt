// ANZ -->
package tachiyomi.domain.remoteserver.model

import tachiyomi.domain.anime.model.Anime
import tachiyomi.domain.source.model.Source as DomainSource

const val REMOTE_SOURCE_ID_OFFSET: Long = 9_000_000_000_000L
const val REMOTE_SOURCE_ID_LIMIT: Long = 9_100_000_000_000L

fun RemoteServer.toSourceId(): Long = REMOTE_SOURCE_ID_OFFSET + id

fun Long.isRemoteServerSourceId(): Boolean = this in (REMOTE_SOURCE_ID_OFFSET + 1) until REMOTE_SOURCE_ID_LIMIT

fun Long.toRemoteServerIdOrNull(): Long? = if (isRemoteServerSourceId()) this - REMOTE_SOURCE_ID_OFFSET else null

fun Anime.isRemoteServer(): Boolean = source.isRemoteServerSourceId()

fun DomainSource.isRemoteServer(): Boolean = id.isRemoteServerSourceId()
// ANZ <--
