package com.anro.beam

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.PI
import kotlin.math.sin

/** A small animated, code-drawn illustration for the first-run walkthrough. */
class TutorialIllustrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var step = 0
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_650L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }

    fun setTutorialStep(value: Int) {
        step = value.coerceIn(0, 2)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        canvas.save()
        canvas.scale(width / 360f, height / 220f)

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            0f, 0f, 360f, 220f,
            intArrayOf(Color.rgb(9, 25, 52), Color.rgb(19, 72, 123), Color.rgb(4, 139, 170)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(0f, 0f, 360f, 220f), 24f, 24f, paint)
        paint.shader = null

        // Soft glows and tiny stars add motion and depth without image assets.
        paint.color = Color.argb((35 + progress * 25).toInt(), 75, 190, 255)
        canvas.drawCircle(180f, 110f, 54f + progress * 10f, paint)
        paint.color = Color.argb(125, 255, 255, 255)
        canvas.drawCircle(28f, 28f, 2.2f, paint)
        canvas.drawCircle(328f, 42f, 1.8f, paint)
        canvas.drawCircle(304f, 190f, 2f, paint)
        canvas.drawCircle(54f, 184f, 1.7f, paint)

        drawPhone(canvas, 43f, 35f, Color.rgb(63, 179, 255), false)
        drawPhone(canvas, 229f, 35f, Color.rgb(64, 218, 171), true)

        when (step) {
            0 -> drawMovingFile(canvas)
            1 -> drawReceivePulse(canvas)
            else -> drawDirectConnection(canvas)
        }

        canvas.restore()
    }

    private fun drawPhone(canvas: Canvas, x: Float, y: Float, accent: Int, receiving: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(238, 244, 249, 255)
        canvas.drawRoundRect(RectF(x, y, x + 88f, y + 150f), 16f, 16f, paint)

        paint.color = Color.rgb(16, 36, 67)
        canvas.drawRoundRect(RectF(x + 5f, y + 9f, x + 83f, y + 141f), 11f, 11f, paint)
        paint.color = accent
        canvas.drawRoundRect(RectF(x + 13f, y + 25f, x + 75f, y + 30f), 3f, 3f, paint)

        // Three file tiles make the illustration read clearly as a sharing app.
        for (i in 0..2) {
            val top = y + 43f + i * 25f
            paint.color = if (receiving && i == 0) Color.argb(245, 54, 195, 156)
                else Color.argb(235, 255, 255, 255)
            canvas.drawRoundRect(RectF(x + 13f, top, x + 26f, top + 15f), 3f, 3f, paint)
            paint.color = Color.argb(125, 230, 241, 255)
            canvas.drawRoundRect(RectF(x + 32f, top + 2f, x + 71f, top + 6f), 2f, 2f, paint)
            canvas.drawRoundRect(RectF(x + 32f, top + 9f, x + 63f, top + 12f), 2f, 2f, paint)
        }

        paint.color = Color.argb(190, 225, 238, 255)
        canvas.drawRoundRect(RectF(x + 36f, y + 4f, x + 52f, y + 6f), 2f, 2f, paint)
        paint.color = accent
        canvas.drawCircle(x + 44f, y + 132f, 3.2f, paint)
    }

    private fun drawMovingFile(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3.5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(145, 174, 224, 255)
        canvas.drawLine(137f, 110f, 223f, 110f, paint)
        paint.style = Paint.Style.FILL

        val x = 139f + progress * 72f
        val y = 94f - (sin(progress * PI).toFloat() * 7f)
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(x, y, x + 28f, y + 34f), 5f, 5f, paint)
        paint.color = Color.rgb(52, 159, 232)
        canvas.drawRoundRect(RectF(x + 6f, y + 9f, x + 22f, y + 12f), 2f, 2f, paint)
        canvas.drawRoundRect(RectF(x + 6f, y + 16f, x + 20f, y + 19f), 2f, 2f, paint)
        canvas.drawRoundRect(RectF(x + 6f, y + 23f, x + 18f, y + 26f), 2f, 2f, paint)

        paint.color = Color.WHITE
        canvas.drawCircle(143f, 159f, 2.5f, paint)
        canvas.drawCircle(216f, 159f, 2.5f, paint)
    }

    private fun drawReceivePulse(canvas: Canvas) {
        val pulse = 13f + progress * 17f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.color = Color.argb((170 * (1f - progress * 0.45f)).toInt(), 92, 245, 193)
        canvas.drawCircle(273f, 111f, pulse, paint)
        canvas.drawCircle(273f, 111f, pulse + 12f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(54, 211, 162)
        canvas.drawCircle(273f, 111f, 14f, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 3f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        val check = Path().apply {
            moveTo(266f, 111f)
            lineTo(271f, 116f)
            lineTo(280f, 105f)
        }
        canvas.drawPath(check, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawDirectConnection(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 3f
        paint.color = Color.argb(205, 105, 221, 255)
        for (i in 0..2) {
            val radius = 14f + i * 10f + progress * 5f
            canvas.drawArc(RectF(150f - radius, 78f - radius, 150f + radius, 78f + radius), -58f, 116f, false, paint)
            canvas.drawArc(RectF(210f - radius, 142f - radius, 210f + radius, 142f + radius), 122f, 116f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(180f, 110f, 4f + progress * 2f, paint)
        paint.color = Color.rgb(176, 224, 255)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 10f
        paint.isFakeBoldText = true
        canvas.drawText("NO INTERNET NEEDED", 180f, 198f, paint)
        paint.isFakeBoldText = false
    }
}
