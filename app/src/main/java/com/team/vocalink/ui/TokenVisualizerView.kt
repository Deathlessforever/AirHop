package com.team.vocalink.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.team.vocalink.core.VadState
import kotlin.math.sin

/**
 * Tactical Token Visualizer & Oscilloscope View:
 * Draws the 13 neural phonemic tokens as discrete level meters alongside
 * real-time audio RMS wave activity and Silero VAD state indicators.
 */
class TokenVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#21262D")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val tokenBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val tokenBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#58A6FF")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B949E")
        textSize = 22f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private val tokens = ByteArray(13) { 0 }
    private var currentRms: Float = 0f
    private var vadState: VadState = VadState.INACTIVE
    private var phase: Float = 0f

    fun updateTokens(newTokens: ByteArray) {
        System.arraycopy(newTokens, 0, tokens, 0, minOf(newTokens.size, 13))
        invalidate()
    }

    fun updateAudioRms(rms: Float, state: VadState) {
        currentRms = rms
        vadState = state
        phase += 0.25f
        if (phase > 2 * Math.PI.toFloat()) {
            phase -= 2 * Math.PI.toFloat()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Tactical grid background
        for (i in 1..3) {
            val y = h * (i / 4f)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // 2. Render 13 Phonemic Token Bars
        val barGap = 6f
        val barWidth = (w - (barGap * 14)) / 13f

        for (i in 0 until 13) {
            val tokenVal = tokens[i].toInt() and 0xFF
            val normalizedHeight = (tokenVal / 64f).coerceIn(0.1f, 1.0f) * (h * 0.7f)

            val left = barGap + i * (barWidth + barGap)
            val top = h - normalizedHeight - 6f
            val right = left + barWidth
            val bottom = h - 6f

            val rect = RectF(left, top, right, bottom)
            tokenBarPaint.color = if (tokenVal > 0) Color.parseColor("#00E5FF") else Color.parseColor("#161B22")
            canvas.drawRect(rect, tokenBarPaint)
            canvas.drawRect(rect, tokenBorderPaint)
        }

        // 3. Render Live Oscilloscope Waveform
        if (currentRms > 10f || vadState == VadState.ACTIVE || vadState == VadState.HANGOVER) {
            wavePaint.color = when (vadState) {
                VadState.ACTIVE -> Color.parseColor("#00E676")   // Green: active speech
                VadState.HANGOVER -> Color.parseColor("#FFD600") // Amber: 450ms hangover drain
                VadState.STARTING -> Color.parseColor("#00E5FF") // Cyan: onset candidate
                else -> Color.parseColor("#58A6FF")
            }

            val points = 60
            val step = w / points
            val midY = h * 0.4f
            val amp = (currentRms / 200f).coerceIn(2f, h * 0.35f)

            var prevX = 0f
            var prevY = midY
            for (p in 0..points) {
                val x = p * step
                val y = midY + sin((p * 0.3f) + phase) * amp
                if (p > 0) {
                    canvas.drawLine(prevX, prevY, x, y, wavePaint)
                }
                prevX = x
                prevY = y
            }
        }

        // 4. VAD Status Label
        val vadLabel = "VAD: ${vadState.name} | RMS: ${currentRms.toInt()}"
        canvas.drawText(vadLabel, 16f, 28f, textPaint)
    }
}
