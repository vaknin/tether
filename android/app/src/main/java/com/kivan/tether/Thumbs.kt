package com.kivan.tether

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.kivan.tether.core.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Chat thumbnails of image files, in filesDir/thumbs/<message id>.webp. They are made when a file
 * is sent or saved, because neither original stays put: the outgoing copy is deleted once the
 * laptop has it, and a received file moves to Downloads, where the user may delete it.
 */
object Thumbs {
    private const val MAX_PX = 720
    private val cache = LruCache<String, ImageBitmap>(40)
    private val _version = MutableStateFlow(0)
    /** Bumped whenever a thumbnail is written, so the chat retries the ones it lacked. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun isImage(name: String?): Boolean = name != null && Downloads.mimeOf(name).startsWith("image/")

    fun cached(id: String): ImageBitmap? = cache.get(id)

    /** Writes the thumbnail of [src] for message [id]; does nothing for non-images. Blocking. */
    fun save(context: Context, id: String, src: File) = save(context, id, ImageDecoder.createSource(src), src.name)

    private fun save(context: Context, id: String, source: ImageDecoder.Source, name: String?) {
        if (!isImage(name)) return
        runCatching {
            // ImageDecoder applies EXIF orientation itself; software memory so it can be compressed.
            val bmp = ImageDecoder.decodeBitmap(source) { d, info, _ ->
                val s = info.size
                val scale = min(1f, MAX_PX.toFloat() / max(s.width, s.height))
                d.setTargetSize(max(1, (s.width * scale).toInt()), max(1, (s.height * scale).toInt()))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val f = file(context, id)
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            cache.put(id, bmp.asImageBitmap())
            _version.update { it + 1 }
        }.onFailure { Log.w("Tether", "thumbnail of $name failed", it) }
    }

    /**
     * The thumbnail of [m], or null. A received image saved before thumbnails existed gets one
     * from its Downloads copy. Blocking.
     */
    fun load(context: Context, m: ChatMessage): ImageBitmap? {
        cache.get(m.id)?.let { return it }
        val f = file(context, m.id)
        if (!f.isFile && !m.fromMe) {
            Downloads.uriFor(context, m.id)?.let { save(context, m.id, ImageDecoder.createSource(context.contentResolver, it), m.fileName) }
            return cache.get(m.id)
        }
        val bmp = BitmapFactory.decodeFile(f.path) ?: return null
        return bmp.asImageBitmap().also { cache.put(m.id, it) }
    }

    /** The thumbnail written for file [id] when it was sent from here, or null. Blocking. */
    fun byId(context: Context, id: String): ImageBitmap? {
        cache.get(id)?.let { return it }
        val f = file(context, id)
        if (!f.isFile) return null
        return BitmapFactory.decodeFile(f.path)?.asImageBitmap()?.also { cache.put(id, it) }
    }

    private fun file(context: Context, id: String) = File(File(context.filesDir, "thumbs"), "$id.webp")
}
