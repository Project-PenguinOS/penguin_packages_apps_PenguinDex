/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.dex.launcher

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * A DeX desktop's dock, as a Mac's: a rounded, translucent shelf just around the apps, floating
 * a little above the bottom of the screen.
 */
object DexDock {
    private const val MARGIN_DP = 4f
    private const val PADDING_DP = 10f
    private const val RADIUS_DP = 16f
    private const val FILL_ALPHA = 170
    private const val EDGE_ALPHA = 40

    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * Draws the dock behind apps spanning [left] to [right], in a bar [height] tall at the bottom
     * of [canvas], in the taskbar's [color] at its [alpha].
     */
    fun draw(
        canvas: Canvas,
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
        fill.color = color
        fill.alpha = alpha * FILL_ALPHA / 255
        canvas.drawRoundRect(rect, radius, radius, fill)
        edge.strokeWidth = density
        edge.color = Color.WHITE
        edge.alpha = alpha * EDGE_ALPHA / 255
        canvas.drawRoundRect(rect, radius, radius, edge)
    }
}
