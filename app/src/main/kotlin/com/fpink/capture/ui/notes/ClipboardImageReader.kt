package com.fpink.capture.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException

sealed interface ClipboardImageResult {
    data class Available(val uri: Uri) : ClipboardImageResult
    data class Unavailable(val message: String) : ClipboardImageResult
}

interface ClipboardImageReader {
    fun currentImage(): ClipboardImageResult
}

class AndroidClipboardImageReader(context: Context) : ClipboardImageReader {
    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)

    override fun currentImage(): ClipboardImageResult {
        val clip = clipboard.primaryClip ?: return ClipboardImageResult.Unavailable(NO_IMAGE)
        if (clip.itemCount == 0) return ClipboardImageResult.Unavailable(NO_IMAGE)
        if (clip.itemCount > 1) return ClipboardImageResult.Unavailable(MULTIPLE_ITEMS)

        val item = clip.getItemAt(0)
        val uri = item.uri ?: return ClipboardImageResult.Unavailable(
            if (clip.description?.hasMimeType("image/*") == true) NON_CONTENT_IMAGE else NO_IMAGE,
        )
        if (uri.scheme != "content") return ClipboardImageResult.Unavailable(NON_CONTENT_IMAGE)

        return try {
            if (!hasImageMimeType(clip, uri)) return ClipboardImageResult.Unavailable(NO_IMAGE)
            appContext.contentResolver.openInputStream(uri)?.use { }
                ?: return ClipboardImageResult.Unavailable(UNREADABLE_IMAGE)
            ClipboardImageResult.Available(uri)
        } catch (_: SecurityException) {
            ClipboardImageResult.Unavailable(UNREADABLE_IMAGE)
        } catch (_: FileNotFoundException) {
            ClipboardImageResult.Unavailable(UNREADABLE_IMAGE)
        } catch (_: IllegalArgumentException) {
            ClipboardImageResult.Unavailable(UNREADABLE_IMAGE)
        } catch (_: IOException) {
            ClipboardImageResult.Unavailable(UNREADABLE_IMAGE)
        }
    }

    private fun hasImageMimeType(clip: ClipData, uri: Uri): Boolean {
        val resolverMime = appContext.contentResolver.getType(uri)
        if (resolverMime != null) return resolverMime.startsWith("image/")
        return clip.description?.hasMimeType("image/*") == true
    }

    companion object {
        const val NO_IMAGE = "Clipboard does not contain an image. Copy one image and try Paste again."
        const val MULTIPLE_ITEMS = "Clipboard contains multiple items. Copy one image and try Paste again."
        const val NON_CONTENT_IMAGE = "Clipboard image is not shared as a readable content URI. Save it locally and choose File."
        const val UNREADABLE_IMAGE = "Android could not read the copied image. The clipboard grant may have expired or been denied. Copy it again or save it locally first."
    }
}
