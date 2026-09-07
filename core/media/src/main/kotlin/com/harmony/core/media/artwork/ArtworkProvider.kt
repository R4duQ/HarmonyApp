package com.harmony.core.media.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Serves artwork to OTHER processes — chiefly the Android Auto host.
 *
 * Why this has to exist: [ArtworkCache] writes to the app's private files
 * dir and hands out `file://` URIs. In-process that's fine, and Coil loads
 * them happily. The Auto host is a separate app, and a `file://` URI into
 * another app's private storage is unreadable to it, so every browse item
 * and the now-playing view rendered with an empty placeholder. A content
 * provider is the supported way across that boundary.
 *
 * And since the bytes pass through here anyway, this is the one place a
 * projected media app CAN influence how its artwork looks: not the shape of
 * the car's image view, which the host owns, but what is drawn inside it.
 * So the `vinyl` path composites the cover onto a record — the closest the
 * platform allows to the phone's player.
 *
 * Two URI forms:
 *   content://{authority}/art/{songId}    plain cover
 *   content://{authority}/vinyl/{songId}  cover as a record label
 *
 * Rendered images are cached to disk. Auto asks for artwork per item, so a
 * hundred-tile grid would otherwise mean a hundred composites on every
 * browse — the difference between a smooth scroll and a stalled one.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String =
        if (uri.pathSegments.firstOrNull() == SEGMENT_VINYL) "image/png" else "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val kind = segments[0]
        val songId = segments[1].substringBefore('.').toLongOrNull() ?: return null

        val userArtwork = uri.getQueryParameter("user") == "true"
        val source = File(File(ctx.filesDir, "artwork"), if (userArtwork) "$songId-user.img" else "$songId.img")
        if (!source.exists()) return null

        val file = when (kind) {
            SEGMENT_VINYL -> renderVinyl(ctx.cacheDir, source, songId)
            else -> renderScaled(ctx.cacheDir, source, songId)
        } ?: return null

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Draws the cover as the label of a record, cached by source timestamp
     * so a re-scan invalidates it but repeat browsing doesn't re-render.
     */
    private fun renderVinyl(cacheDir: File, source: File, songId: Long): File? {
        val dir = File(cacheDir, "vinyl").apply { mkdirs() }
        val out = File(dir, "${source.name}-${source.lastModified()}.png")
        if (out.exists()) return out

        val cover = decodeScaled(source, SIZE) ?: return null

        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = SIZE / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Disc.
        paint.color = VINYL_BLACK
        canvas.drawCircle(c, c, c, paint)

        // A subtle sheen so the disc doesn't read as a flat black hole on a
        // dark car UI, where a pure circle would nearly disappear.
        paint.shader = RadialGradient(
            c * 0.65f, c * 0.65f, c * 1.3f,
            intArrayOf(0x33FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(c, c, c, paint)
        paint.shader = null

        // Grooves, tightening toward the rim as on a real pressing.
        paint.style = Paint.Style.STROKE
        paint.color = 0x22FFFFFF
        paint.strokeWidth = SIZE * 0.004f
        for (i in 0 until GROOVES) {
            val t = i / (GROOVES - 1f)
            canvas.drawCircle(c, c, c * (0.42f + 0.55f * t * t), paint)
        }
        paint.style = Paint.Style.FILL

        // Label: the cover, circular-cropped.
        val labelR = c * LABEL_FRACTION
        val label = Bitmap.createBitmap(
            (labelR * 2).toInt(), (labelR * 2).toInt(), Bitmap.Config.ARGB_8888,
        )
        Canvas(label).apply {
            val lp = Paint(Paint.ANTI_ALIAS_FLAG)
            drawCircle(labelR, labelR, labelR, lp)
            lp.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            drawBitmap(
                cover,
                Rect(0, 0, cover.width, cover.height),
                RectF(0f, 0f, labelR * 2, labelR * 2),
                lp,
            )
        }
        canvas.drawBitmap(label, c - labelR, c - labelR, null)
        label.recycle()

        // Spindle hole. Transparent, not a colour: the car's background is
        // whatever the host decides, so punching through is the only way to
        // read as a hole rather than a dot.
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        canvas.drawCircle(c, c, SIZE * 0.022f, paint)
        paint.xfermode = null

        return try {
            writeAtomically(out) { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cover.recycle()
            bmp.recycle()
            prune(dir)
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The cover, re-encoded at a sane size.
     *
     * Embedded art is routinely 1–3 MB, which is wasted on a car screen and
     * slow to hand across the binder for every tile in a grid. JPEG at
     * [SIZE] px is a fraction of that and visually identical at the sizes
     * involved.
     */
    private fun renderScaled(cacheDir: File, source: File, songId: Long): File? {
        val dir = File(cacheDir, "art").apply { mkdirs() }
        val out = File(dir, "${source.name}-${source.lastModified()}.jpg")
        if (out.exists()) return out
        val bmp = decodeScaled(source, SIZE) ?: return null
        return try {
            writeAtomically(out) { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            prune(dir)
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes to a temporary file and renames it into place.
     *
     * Writing straight to [target] would make it exist — and therefore look
     * cached — from the first byte. Two processes ask for the same artwork
     * the moment Auto connects (the car and the notification), so one would
     * start writing while the other saw the file, opened it, and served a
     * half-written image. Rename is atomic, so a reader sees either nothing
     * or the finished file.
     *
     * The temp name carries the thread id, so two concurrent renders of the
     * same artwork can't scribble over each other's partial output either.
     */
    private inline fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val tmp = File(target.parentFile, "${target.name}.${Thread.currentThread().id}.tmp")
        try {
            tmp.outputStream().use(write)
            if (!tmp.renameTo(target)) {
                // Cross-filesystem or a racing writer already got there;
                // a copy still leaves the target complete-or-absent.
                tmp.copyTo(target, overwrite = true)
            }
        } finally {
            tmp.delete()
        }
    }

    /** Decodes no larger than needed — covers are often far bigger than a car screen. */
    private fun decodeScaled(file: File, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /** Keeps the render cache bounded; oldest go first. */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.filterNot { it.name.endsWith(".tmp") } ?: return
        if (files.size <= MAX_CACHED) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED)
            .forEach { it.delete() }
    }

    // Read-only provider: everything below is unsupported by design.
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.harmony.app.artwork"
        private const val SEGMENT_VINYL = "vinyl"
        private const val SEGMENT_ART = "art"

        private const val SIZE = 512
        private const val GROOVES = 22
        private const val LABEL_FRACTION = 0.38f
        private const val VINYL_BLACK = 0xFF17151C.toInt()
        private const val MAX_CACHED = 250

        /** Cover composited onto a record. */
        fun vinylUri(songId: Long, sourceUri: String? = null): String =
            "content://$AUTHORITY/$SEGMENT_VINYL/$songId.png" + userQuery(sourceUri)

        /** The plain cover. */
        fun artUri(songId: Long, sourceUri: String? = null): String =
            "content://$AUTHORITY/$SEGMENT_ART/$songId.jpg" + userQuery(sourceUri)

        private fun userQuery(sourceUri: String?): String =
            if (sourceUri?.endsWith("-user.img") == true) "?user=true" else ""
    }
}
