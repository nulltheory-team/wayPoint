package `in`.nulltheory.waypoint.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import `in`.nulltheory.waypoint.R
import kotlin.math.max

/**
 * Route progress drawn as discrete ticks rather than one continuous bar.
 *
 * At a glance across a bench a segmented bar reads as "how far along" far faster than a solid
 * fill, because the eye counts blocks instead of estimating a length. It also makes slow
 * progress visible: at 1 Hz on a 25 km route a solid bar looks frozen, whereas a segment
 * lighting up is unambiguous movement.
 */
class SegmentedProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val unlitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.divider)
    }

    private val density = resources.displayMetrics.density
    private val targetSegmentWidth = 13f * density
    private val gap = 4f * density
    private val corner = 1.5f * density

    /** 0..1 along the route. */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Segment count follows the available width, so the bar looks right on a phone and on
        // a 1920px dashcam without a per-layout constant.
        val count = max(1, ((w + gap) / (targetSegmentWidth + gap)).toInt())
        val segment = (w - gap * (count - 1)) / count
        val lit = progress * count

        for (i in 0 until count) {
            val left = i * (segment + gap)
            canvas.drawRoundRect(
                left, 0f, left + segment, h, corner, corner,
                if (i < lit) litPaint else unlitPaint
            )
        }
    }
}
