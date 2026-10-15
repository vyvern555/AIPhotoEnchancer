package com.aienhancer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

object ImageUtils {

    /** Максимальная сторона результата, чтобы не упасть по памяти. */
    const val MAX_OUTPUT_SIDE = 4096

    /** Максимальная сторона входного фото для заданного апскейла (1, 2 или 4). */
    fun maxInputSideFor(scale: Int): Int = MAX_OUTPUT_SIDE / scale

    /**
     * Загружает фото из Uri: уменьшает до maxSide и поворачивает по EXIF.
     */
    fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Не удалось прочитать изображение" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Не удалось открыть изображение")

        val degrees = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        if (scale == 1f && degrees == 0f) return decoded

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees)
        }
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    /**
     * Bitmap -> float-данные в формате NCHW (1 x 3 x H x W), RGB, значения 0..1.
     */
    fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val plane = w * h
        val pixels = IntArray(plane)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val buffer = ByteBuffer.allocateDirect(3 * plane * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (i in 0 until plane) {
            val p = pixels[i]
            buffer.put(i, ((p shr 16) and 0xFF) / 255f)
            buffer.put(plane + i, ((p shr 8) and 0xFF) / 255f)
            buffer.put(2 * plane + i, (p and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Float-данные NCHW (1 x 3 x H x W, 0..1) -> Bitmap.
     */
    fun floatBufferToBitmap(data: FloatBuffer, width: Int, height: Int): Bitmap {
        val plane = width * height
        val pixels = IntArray(plane)
        for (i in 0 until plane) {
            val r = (data.get(i).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val g = (data.get(plane + i).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val b = (data.get(2 * plane + i).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * Плиточная обработка. Фото режется на куски tileSize x tileSize с запасом
     * overlap по краям (чтобы не было швов), каждый кусок проходит через process,
     * затем результат собирается обратно.
     *
     * scale — во сколько раз process увеличивает кусок (1 для denoise/deblur, 2 или 4 для апскейла).
     * process должен вернуть Bitmap размером (ширина * scale) x (высота * scale).
     */
    fun processInTiles(
        source: Bitmap,
        scale: Int,
        tileSize: Int = 256,
        overlap: Int = 16,
        onProgress: (Float) -> Unit = {},
        process: (Bitmap) -> Bitmap
    ): Bitmap {
        val width = source.width
        val height = source.height
        val result = Bitmap.createBitmap(width * scale, height * scale, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        val tilesX = (width + tileSize - 1) / tileSize
        val tilesY = (height + tileSize - 1) / tileSize
        val total = tilesX * tilesY
        var done = 0

        var y0 = 0
        while (y0 < height) {
            val coreH = min(tileSize, height - y0)
            var x0 = 0
            while (x0 < width) {
                val coreW = min(tileSize, width - x0)

                val px0 = max(0, x0 - overlap)
                val py0 = max(0, y0 - overlap)
                val px1 = min(width, x0 + coreW + overlap)
                val py1 = min(height, y0 + coreH + overlap)

                val tile = Bitmap.createBitmap(source, px0, py0, px1 - px0, py1 - py0)
                val out = process(tile)

                val srcRect = Rect(
                    (x0 - px0) * scale,
                    (y0 - py0) * scale,
                    (x0 - px0 + coreW) * scale,
                    (y0 - py0 + coreH) * scale
                )
                val dstRect = Rect(
                    x0 * scale,
                    y0 * scale,
                    (x0 + coreW) * scale,
                    (y0 + coreH) * scale
                )
                canvas.drawBitmap(out, srcRect, dstRect, paint)

                if (out !== tile) out.recycle()
                tile.recycle()

                done++
                onProgress(done.toFloat() / total)
                x0 += tileSize
            }
            y0 += tileSize
        }
        return result
    }

    /**
     * Смешивает оригинал и улучшенное фото.
     * strength = 0f -> оригинал, 1f -> полностью улучшенное.
     */
    fun blend(original: Bitmap, enhanced: Bitmap, strength: Float): Bitmap {
        val s = strength.coerceIn(0f, 1f)
        val result = enhanced.copy(Bitmap.Config.ARGB_8888, true)
        if (s >= 1f) return result

        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            alpha = ((1f - s) * 255f).toInt()
        }
        Canvas(result).drawBitmap(
            original,
            Rect(0, 0, original.width, original.height),
            Rect(0, 0, result.width, result.height),
            paint
        )
        return result
    }
}
