package com.mixsphere.dj.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.Button
import kotlin.math.roundToInt

object Theme {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    val BG0 = Color.parseColor("#0A0D12")
    val BG1 = Color.parseColor("#111720")
    val BG2 = Color.parseColor("#19232E")
    val BG3 = Color.parseColor("#2A3947")
    val SCOPE = Color.parseColor("#080B10")
    val GROOVE = Color.parseColor("#07090D")
    val GRID = Color.parseColor("#1B2833")
    val TEXT = Color.parseColor("#EEF4F7")
    val DIM = Color.parseColor("#91A0AD")
    val ACCENT = Color.parseColor("#D7FF55")
    val DECK_A = Color.parseColor("#FF7058")
    val DECK_B = Color.parseColor("#48D9BE")
    val GREEN = Color.parseColor("#B7F266")
    val RED = Color.parseColor("#FF5868")

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun rounded(color: Int, radius: Float = 8f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    fun button(
        context: Context,
        text: String,
        color: Int = TEXT,
        background: Int = BG2,
        size: Float = 11f,
        onClick: (Button) -> Unit,
    ): Button = Button(context).apply {
        this.text = text
        textSize = size
        isAllCaps = true
        setTextColor(color)
        this.background = rounded(background)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        letterSpacing = 0.04f
        stateListAnimator = null
        setOnClickListener { onClick(this) }
        minimumHeight = 0
        setPadding(4, 4, 4, 4)
    }
}
