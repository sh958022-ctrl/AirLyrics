package com.andsi.airlyrics.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.min
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withSave
import kotlin.math.roundToInt

/** Unambiguous separator between the current and next lyric (newlines remain legal). */
internal const val CAMERA_SPLIT_SEPARATOR: Char = '\\u0001'

/**
 * Lets the renderer animate lyric glyphs without transforming the floating bubble itself.
 *
 * A TextView draws its background before [onDraw], so applying the transition only while
 * delegating to TextView's content drawing keeps the background fully visible and stationary.
 */
internal class FloatingLyricsTextView(context: Context) : AppCompatTextView(context),
    LyricsTextAnimationTarget {

    var outlineWidthPx: Float = 0f
        set(value) { field = value.coerceAtLeast(0f); invalidate() }
    var outlineColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    var cameraSplitEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            splitSlideAnimator?.cancel()
            splitSlideAnimator = null
            splitCurrent = ""
            splitNext = ""
            slideFraction = 1f
            invalidate()
        }
    var cameraCenterX = 0f
    var cameraHalfGap = 0f
    var cameraSideReserveFraction = 0.23f
    private var splitCurrent = ""
    private var splitNext = ""
    private var slidingOldCurrent = ""
    private var slidingOldNext = ""
    private var slidingIncomingNext = ""
    private var slideFraction = 1f
    private var splitSlideAnimator: ValueAnimator? = null

    internal var onWindowConfigurationChanged: (() -> Unit)? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onWindowConfigurationChanged?.invoke()
    }

    private var lyricContentAlpha: Float = REST_ALPHA
    private var lyricContentTranslationY: Float = 0f
    private var lyricContentScaleX: Float = REST_SCALE
    private var lyricContentScaleY: Float = REST_SCALE

    private var lyricAnimator: ValueAnimator? = null

    override fun cancelLyricsTextAnimation() {
        val animator = lyricAnimator ?: return
        lyricAnimator = null
        animator.cancel()
    }

    override fun setLyricsTextAnimationState(
        alpha: Float,
        translationY: Float,
        scaleX: Float,
        scaleY: Float
    ) {
        lyricContentAlpha = alpha.coerceIn(0f, 1f)
        lyricContentTranslationY = translationY
        lyricContentScaleX = scaleX.coerceAtLeast(0f)
        lyricContentScaleY = scaleY.coerceAtLeast(0f)
        invalidate()
    }

    override fun animateLyricsTextToRest(
        durationMs: Long,
        interpolator: TimeInterpolator
    ) {
        cancelLyricsTextAnimation()

        val startAlpha = lyricContentAlpha
        val startTranslationY = lyricContentTranslationY
        val startScaleX = lyricContentScaleX
        val startScaleY = lyricContentScaleY
        if (
            startAlpha == REST_ALPHA &&
            startTranslationY == 0f &&
            startScaleX == REST_SCALE &&
            startScaleY == REST_SCALE
        ) {
            return
        }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            this.interpolator = interpolator
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                setLyricsTextAnimationState(
                    alpha = lerp(startAlpha, REST_ALPHA, fraction),
                    translationY = lerp(startTranslationY, 0f, fraction),
                    scaleX = lerp(startScaleX, REST_SCALE, fraction),
                    scaleY = lerp(startScaleY, REST_SCALE, fraction)
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (lyricAnimator !== animation) return
                    lyricAnimator = null
                    setLyricsTextAnimationState(
                        alpha = REST_ALPHA,
                        translationY = 0f,
                        scaleX = REST_SCALE,
                        scaleY = REST_SCALE
                    )
                }
            })
        }
        lyricAnimator = animator
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        if (cameraSplitEnabled && text?.contains(CAMERA_SPLIT_SEPARATOR) == true) {
            drawCameraSplit(canvas)
            return
        }
        if (lyricContentAlpha <= 0f) return

        canvas.withSave {
            translate(0f, lyricContentTranslationY)
            scale(
                lyricContentScaleX,
                lyricContentScaleY,
                width / 2f,
                height / 2f
            )
            // TextView resets paint.color from its ColorStateList in onDraw.
            // Temporarily override that list for the stroke pass, then restore it.
            val alpha = (lyricContentAlpha * 255f).roundToInt()
            if (alpha < 255) {
                saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
            }
            if (outlineWidthPx > 0f) {
                val originalColors = textColors
                val originalStyle = paint.style
                val originalStroke = paint.strokeWidth
                val originalJoin = paint.strokeJoin
                try {
                    setTextColor(outlineColor)
                    paint.style = Paint.Style.STROKE
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.strokeWidth = outlineWidthPx * 2f
                    super.onDraw(this)
                } finally {
                    paint.style = originalStyle
                    paint.strokeJoin = originalJoin
                    paint.strokeWidth = originalStroke
                    setTextColor(originalColors)
                }
            }
            super.onDraw(this)

        }
    }

    private fun drawCameraSplit(canvas: Canvas) {
        val pair = text.toString().split(CAMERA_SPLIT_SEPARATOR, limit = 2)
        val current = pair.getOrElse(0) { "" }
        val next = pair.getOrElse(1) { "" }

        if (current != splitCurrent || next != splitNext) {
            val advances = splitCurrent.isNotEmpty() && current != splitCurrent &&
                current == splitNext
            splitSlideAnimator?.cancel()
            splitSlideAnimator = null
            if (advances) {
                slidingOldCurrent = splitCurrent
                slidingOldNext = splitNext
                slidingIncomingNext = next
                slideFraction = 0f
                splitSlideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 260L
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { value ->
                        slideFraction = value.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                slideFraction = 1f
            }
            splitCurrent = current
            splitNext = next
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val reserve = (w * cameraSideReserveFraction).coerceAtMost(w * 0.32f)
        val center = cameraCenterX.takeIf { it in 1f..w } ?: w / 2f
        val gap = cameraHalfGap.coerceAtLeast(resources.displayMetrics.density * 15f)
        val leftMin = reserve
        val leftMax = (center - gap).coerceAtLeast(leftMin)
        val rightMin = (center + gap).coerceAtMost(w - reserve)
        val rightMax = w - reserve
        val slotWidth = min(leftMax - leftMin, rightMax - rightMin).toInt().coerceAtLeast(1)
        val leftCenter = (leftMin + leftMax) / 2f
        val rightCenter = (rightMin + rightMax) / 2f

        // All moving text is clipped to the free portions of the status bar.
        // Never paint over the camera hole, clock, or system icon reservations.
        canvas.withSave {
            clipRect(leftMin, 0f, rightMax, h)
            if (Build.VERSION.SDK_INT >= 26) {
                clipOutRect(RectF(center - gap, 0f, center + gap, h))
            }
            if (slideFraction < 1f) {
                val f = slideFraction
                drawMovingLyric(this, slidingOldCurrent, leftCenter - slotWidth * f, slotWidth, h, 1f - f)
                drawMovingLyric(this, slidingOldNext, rightCenter + (leftCenter - rightCenter) * f, slotWidth, h, 1f)
                drawMovingLyric(this, slidingIncomingNext, rightCenter + slotWidth * (1f - f), slotWidth, h, f)
            } else {
                drawMovingLyric(this, current, leftCenter, slotWidth, h, 1f)
                drawMovingLyric(this, next, rightCenter, slotWidth, h, 0.8f)
            }
        }
    }

    private fun drawMovingLyric(
        canvas: Canvas, content: String, centerX: Float, slotWidth: Int, heightPx: Float,
        opacity: Float
    ) {
        if (content.isBlank() || opacity <= 0f) return
        val textPaint = TextPaint(paint).apply {
            style = Paint.Style.FILL
            color = Color.argb(
                (Color.alpha(currentTextColor) * opacity).toInt().coerceIn(0, 255),
                Color.red(currentTextColor), Color.green(currentTextColor), Color.blue(currentTextColor)
            )
        }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, textPaint, slotWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(slotWidth)
            .build()
        canvas.withSave {
            translate(centerX - slotWidth / 2f, (heightPx - layout.height) / 2f)
            if (outlineWidthPx > 0f) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeJoin = Paint.Join.ROUND
                textPaint.strokeWidth = 2f * outlineWidthPx
                textPaint.color = Color.argb(
                    (Color.alpha(outlineColor) * opacity).toInt().coerceIn(0, 255),
                    Color.red(outlineColor), Color.green(outlineColor), Color.blue(outlineColor)
                )
                layout.draw(this)
                textPaint.style = Paint.Style.FILL
                textPaint.color = Color.argb(
                    (Color.alpha(currentTextColor) * opacity).toInt().coerceIn(0, 255),
                    Color.red(currentTextColor), Color.green(currentTextColor), Color.blue(currentTextColor)
                )
            }
            layout.draw(this)
        }
    }

    override fun onDetachedFromWindow() {
        splitSlideAnimator?.cancel()
        splitSlideAnimator = null
        cancelLyricsTextAnimation()
        setLyricsTextAnimationState(
            alpha = REST_ALPHA,
            translationY = 0f,
            scaleX = REST_SCALE,
            scaleY = REST_SCALE
        )
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private companion object {
        const val REST_ALPHA = 1f
        const val REST_SCALE = 1f
    }
}

internal interface LyricsTextAnimationTarget {
    fun cancelLyricsTextAnimation()

    fun setLyricsTextAnimationState(
        alpha: Float,
        translationY: Float,
        scaleX: Float,
        scaleY: Float
    )

    fun animateLyricsTextToRest(durationMs: Long, interpolator: TimeInterpolator)
}
