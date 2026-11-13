package com.dynamicisland.dilink.link

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * Картинки с компьютера складываются прямо в галерею телефона.
 *
 * На Android 10+ пишем через `MediaStore` — разрешение не нужно, файл сразу
 * виден в «Фото». На старых версиях копия идёт в общую папку `Pictures/DI Link`,
 * а галерея узнаёт о ней через `MediaScannerConnection`. Если доступа к общей
 * папке нет, картинка остаётся в «Загрузках» — как раньше.
 */
class GallerySaver(private val context: Context) {

    /** Что получилось: [uri] — адрес в галерее, [path] — где файл на диске. */
    data class Saved(
        val uri: Uri?,
        val path: String,
        val location: String
    )

    /** Кладёт файл в галерею. null — сохранить не вышло, файл остаётся в «Загрузках». */
    fun save(source: File, mime: String): Saved? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveScoped(source, mime)
        else saveLegacy(source, mime)

    private fun saveScoped(source: File, mime: String): Saved? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Saved(
            uri = uri,
            path = "${Environment.getExternalStorageDirectory()}/$FOLDER/${source.name}",
            location = "${Environment.DIRECTORY_PICTURES}/$FOLDER"
        )
    }

    private fun saveLegacy(source: File, mime: String): Saved? {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            FOLDER
        )
        if (!folder.exists() && !folder.mkdirs()) return null
        if (!folder.canWrite()) return null
        val target = uniqueFile(folder, source.name)
        return try {
            source.copyTo(target, overwrite = true)
            // Галерея узнаёт о файле чуть позже — на это время у нас есть путь.
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(mime),
                null
            )
            Saved(uri = null, path = target.absolutePath, location = "${Environment.DIRECTORY_PICTURES}/$FOLDER")
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    companion object {
        const val FOLDER = "DI Link"

        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"
        )

        /** Картинка ли это — по расширению имени файла. */
        fun isImage(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

        /** MIME-тип по расширению: галерея записывает его в медиатеку. */
        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> "image/*"
        }

        private fun uniqueFile(folder: File, name: String): File {
            var candidate = File(folder, name)
            if (!candidate.exists()) return candidate
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var index = 1
            while (true) {
                candidate = File(folder, "$stem ($index)$ext")
                if (!candidate.exists()) return candidate
                index++
            }
        }
    }
}
