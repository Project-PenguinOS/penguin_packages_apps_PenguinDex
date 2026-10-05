/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.dex.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.WindowManager
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * A DeX desktop's dock, as a Mac's: a rounded shelf of frosted glass just around the apps,
 * floating a little above the bottom of the screen, the desktop blurred behind it.
 */
object DexDock {
    private const val MARGIN_DP = 4f
    private const val PADDING_DP = 10f
    private const val RADIUS_DP = 18f
    private const val BLUR_DP = 30
    // Lighter than the taskbar alone, so the blur shows through.
    private const val FILL_ALPHA = 105
    private const val FALLBACK_ALPHA = 190
    private const val EDGE_ALPHA = 46

    private val rect = RectF()
    private val bounds = Rect()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blurs = WeakHashMap<View, BackgroundBlurDrawable>()

    /**
     * Draws the dock behind apps spanning [left] to [right], in a bar [height] tall at the bottom
     * of [canvas], in the taskbar's [color] at its [alpha]. [host] is the taskbar view, whose
     * window blurs the desktop behind the dock.
     */
    fun draw(
        canvas: Canvas,
        host: View,
        left: Float,
        right: Float,
        height: Float,
        color: Int,
        alpha: Int,
        density: Float,
    ) {
        val margin = MARGIN_DP * density
        val padding = PADDING_DP * density
        rect.set(left - padding, canvas.height - height + margin, right + padding, canvas.height - margin)
        val radius = RADIUS_DP * density

        val blur = blurFor(host, radius, density)
        if (blur != null) {
            rect.roundOut(bounds)
            blur.setBounds(bounds)
            blur.alpha = alpha
            blur.draw(canvas)
        }

        fill.color = color
        fill.alpha = alpha * (if (blur != null) FILL_ALPHA else FALLBACK_ALPHA) / 255
        canvas.drawRoundRect(rect, radius, radius, fill)
        edge.strokeWidth = density
        edge.color = Color.WHITE
        edge.alpha = alpha * EDGE_ALPHA / 255
        canvas.drawRoundRect(rect, radius, radius, edge)
    }

    private fun blurFor(host: View, radius: Float, density: Float): BackgroundBlurDrawable? {
        val windows = host.context.getSystemService(WindowManager::class.java)
        if (windows == null || !windows.isCrossWindowBlurEnabled) return null
        blurs[host]?.let { return it }
        val viewRoot = host.viewRootImpl ?: return null
        return viewRoot.createBackgroundBlurDrawable().apply {
            setBlurRadius((BLUR_DP * density).roundToInt())
            setCornerRadius(radius)
            setColor(Color.TRANSPARENT)
            blurs[host] = this
        }
    }
}
