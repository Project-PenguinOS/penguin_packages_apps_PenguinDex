/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.dex

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.VirtualMouse
import android.hardware.input.VirtualMouseButtonEvent
import android.hardware.input.VirtualMouseRelativeEvent
import android.hardware.input.VirtualMouseScrollEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Turns touches into the pointer's moves: one finger moves it, a tap clicks, a two finger tap
 * right clicks, two fingers scroll, and a tap followed by a drag holds the button down.
 */
@SuppressLint("ViewConstructor")
class TouchpadView(context: Context, private val mouse: VirtualMouse) : View(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var maxPointers = 0
    private var moved = false
    private var dragging = false
    private var scrollRemainder = 0f
    // The mouse moves in whole pixels; what's left of each move is carried into the next.
    private var remainderX = 0f
    private var remainderY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downTime = event.eventTime
                maxPointers = 1
                moved = false
                scrollRemainder = 0f
                // A second touch soon after a tap, where it was, picks up what's under the pointer.
                if (event.eventTime - lastTapUp < DOUBLE_TAP_MS &&
                    hypot(event.x - lastTapX, event.y - lastTapY) < slop * 4
                ) {
                    dragging = true
                    button(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_BUTTON_PRESS)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                lastX = averageX(event)
                lastY = averageY(event)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Carry on from the fingers still down, so the pointer doesn't jump.
                val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                lastX = remaining.map { event.getX(it) }.average().toFloat()
                lastY = remaining.map { event.getY(it) }.average().toFloat()
            }
            MotionEvent.ACTION_MOVE -> {
                val x = averageX(event)
                val y = averageY(event)
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                if (!moved && hypot(event.x - downX, event.y - downY) > slop) moved = true
                if (event.pointerCount >= 2) scroll(dy) else move(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                val quick = event.eventTime - downTime < TAP_MS
                when {
                    dragging -> {
                        dragging = false
                        button(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_BUTTON_RELEASE)
                    }
                    !moved && quick && maxPointers >= 2 -> click(MotionEvent.BUTTON_SECONDARY)
                    !moved && quick -> {
                        click(MotionEvent.BUTTON_PRIMARY)
                        lastTapUp = event.eventTime
                        lastTapX = event.x
                        lastTapY = event.y
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) button(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_BUTTON_RELEASE)
                dragging = false
            }
        }
        return true
    }

    private fun averageX(event: MotionEvent) =
        (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()

    private fun averageY(event: MotionEvent) =
        (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()

    /** Moves the pointer, faster for a quick flick, as a laptop's touchpad does. */
    private fun move(dx: Float, dy: Float) {
        val speed = hypot(dx, dy) / density
        val gain = BASE_GAIN + min(speed, 24f) * ACCELERATION
        remainderX += dx / density * gain
        remainderY += dy / density * gain
        val stepX = remainderX.toInt()
        val stepY = remainderY.toInt()
        if (stepX == 0 && stepY == 0) return
        remainderX -= stepX
        remainderY -= stepY
        mouse.sendRelativeEvent(
            VirtualMouseRelativeEvent.Builder()
                .setRelativeX(stepX.toFloat())
                .setRelativeY(stepY.toFloat())
                .build()
        )
    }

    /** Scrolls a notch for every so many dp the fingers move, the content following them. */
    private fun scroll(dy: Float) {
        scrollRemainder += dy / density / SCROLL_DP
        val notches = scrollRemainder.coerceIn(-1f, 1f)
        if (abs(notches) < 0.1f) return
        scrollRemainder -= notches
        mouse.sendScrollEvent(VirtualMouseScrollEvent.Builder().setYAxisMovement(notches).build())
    }

    private fun click(code: Int) {
        button(code, MotionEvent.ACTION_BUTTON_PRESS)
        button(code, MotionEvent.ACTION_BUTTON_RELEASE)
    }

    private fun button(code: Int, action: Int) {
        mouse.sendButtonEvent(VirtualMouseButtonEvent.Builder().setButtonCode(code).setAction(action).build())
    }

    private companion object {
        const val TAP_MS = 220L
        const val DOUBLE_TAP_MS = 280L
        const val BASE_GAIN = 2.6f
        const val ACCELERATION = 0.22f
        const val SCROLL_DP = 28f
    }
}
