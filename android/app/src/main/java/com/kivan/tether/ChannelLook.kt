package com.kivan.tether

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.PathParser
import androidx.core.graphics.withTranslation
import com.kivan.tether.ui.theme.Palette
import org.json.JSONObject

/** A channel's `icon`: white shapes in `view` (x, y, w, h), as the daemon's `mark.rs` sends them. */
class ChannelMark(val view: FloatArray, val paths: List<Shape>) {
    class Shape(val path: Path, val fill: Boolean, val stroke: Float)

    companion object {
        fun parse(o: JSONObject?): ChannelMark? {
            o ?: return null
            val v = o.optJSONArray("view")?.takeIf { it.length() == 4 } ?: return null
            val view = FloatArray(4) { v.optDouble(it).toFloat() }
            if (view[2] <= 0f || view[3] <= 0f) return null
            val arr = o.optJSONArray("paths") ?: return null
            val shapes = (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = runCatching { PathParser.createPathFromPathData(p.optString("d")) }.getOrNull() ?: return@mapNotNull null
                Shape(path, p.optBoolean("fill"), p.optDouble("stroke", 0.0).toFloat())
            }
            return if (shapes.isEmpty()) null else ChannelMark(view, shapes)
        }
    }
}

/**
 * How a channel's tile looks (docs/DESIGN.md): with a `hue`, a white mark on the hue's tile,
 * like a launcher icon. Older manifests with only `accent` keep a dark glyph on the accent.
 */
object ChannelLook {
    fun background(c: ChannelInfo): Int = c.tile ?: c.accent ?: Palette.Tile.toArgb()

    fun foreground(c: ChannelInfo): Int =
        if (c.tile == null && c.accent != null) c.onAccent ?: Palette.OnAccent.toArgb() else Palette.Text.toArgb()

    /** The tile, its mark (or glyph) in the middle half, into a `size` square at (x, y). */
    fun draw(canvas: Canvas, c: ChannelInfo, x: Float, y: Float, size: Float) {
        val r = size / 2
        canvas.drawCircle(x + r, y + r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background(c) })
        val fg = foreground(c)
        val mark = c.icon
        if (mark != null) {
            drawMark(canvas, mark, x + size * 0.25f, y + size * 0.25f, size * 0.5f, fg)
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fg
                textSize = size * 0.5f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(c.glyph, x + r, y + r - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }

    /** [mark] fitted into a `box` square at (x, y), centred. */
    fun drawMark(canvas: Canvas, mark: ChannelMark, x: Float, y: Float, box: Float, color: Int) {
        val (vx, vy, vw, vh) = mark.view
        val k = box / maxOf(vw, vh)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.withTranslation(x + (box - vw * k) / 2, y + (box - vh * k) / 2) {
            scale(k, k)
            translate(-vx, -vy)
            for (s in mark.paths) {
                if (s.fill) drawPath(s.path, paint.apply { style = Paint.Style.FILL })
                if (s.stroke > 0f) drawPath(s.path, paint.apply { style = Paint.Style.STROKE; strokeWidth = s.stroke })
            }
        }
    }
}
