package com.youlldownload.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val SAVE_FOLDER = "Youlldownload"

/** Copies the finished file to Downloads/Youlldownload and returns a content Uri for open/share. */
object Saver {
    fun save(context: Context, file: File, mime: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveQ(context, file, mime)
        else saveLegacy(context, file, mime)
    }

    private fun saveQ(context: Context, file: File, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SAVE_FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("Couldn't create the file in Downloads.")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File, mime: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SAVE_FOLDER)
        dir.mkdirs()
        var dest = File(dir, file.name)
        var i = 1
        while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} ($i).${file.extension}").also { i++ }
        file.copyTo(dest)

        var result: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime)) { _, uri ->
            result = uri
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return result ?: throw RuntimeException("Saved to ${dest.absolutePath}, but couldn't index it.")
    }
}
