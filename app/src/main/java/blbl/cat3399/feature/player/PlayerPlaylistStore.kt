package blbl.cat3399.feature.player

import blbl.cat3399.core.log.AppLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PlayerPlaylist(
    val items: List<PlayerPlaylistItem>,
    val source: String?,
    val createdAtMs: Long,
    var index: Int,
)

object PlayerPlaylistStore {
    private const val MAX_PLAYLISTS = 30

    private val store = ConcurrentHashMap<String, PlayerPlaylist>()
    private val order = ArrayDeque<String>()
    private val lock = Any()

    fun put(items: List<PlayerPlaylistItem>, index: Int, source: String? = null): String {
        val sanitized = items.filter { it.bvid.isNotBlank() }
        val safeIndex = index.coerceIn(0, (sanitized.size - 1).coerceAtLeast(0))
        val token = UUID.randomUUID().toString()
        val playlist =
            PlayerPlaylist(
                items = sanitized,
                source = source,
                createdAtMs = System.currentTimeMillis(),
                index = safeIndex,
            )
        store[token] = playlist
        synchronized(lock) {
            order.addLast(token)
            trimLocked()
        }
        AppLog.d("PlayerPlaylistStore", "put size=${sanitized.size} idx=$safeIndex source=${source.orEmpty()} token=${token.take(8)}")
        return token
    }

    fun get(token: String): PlayerPlaylist? {
        if (token.isBlank()) return null
        return store[token]
    }

    fun updateIndex(token: String, index: Int) {
        if (token.isBlank()) return
        val p = store[token] ?: return
        p.index = index.coerceIn(0, (p.items.size - 1).coerceAtLeast(0))
    }

    fun remove(token: String) {
        if (token.isBlank()) return
        store.remove(token)
        synchronized(lock) {
            order.remove(token)
        }
    }

    private fun trimLocked() {
        while (order.size > MAX_PLAYLISTS) {
            val oldest = order.removeFirstOrNull() ?: break
            store.remove(oldest)
        }
    }
}

