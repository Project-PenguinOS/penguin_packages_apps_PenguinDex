/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.dex

import android.app.Activity
import android.graphics.Color
import android.hardware.input.InputManager
import android.hardware.input.VirtualMouse
import android.hardware.input.VirtualMouseButtonEvent
import android.hardware.input.VirtualMouseConfig
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The phone as the DeX desktop's touchpad, through a mouse of its own on that screen. */
class TouchpadActivity : Activity() {

    private var mouse: VirtualMouse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.setBackgroundColor(Color.BLACK)

        val display = dexDisplays(this).firstOrNull()
        if (display == null) {
            setContentView(label(getString(R.string.touchpad_no_display), 0.8f))
            return
        }
        mouse =
            getSystemService(InputManager::class.java)
                .createVirtualMouse(
                    VirtualMouseConfig.Builder()
                        .setInputDeviceName("PenguinOS DeX touchpad")
                        .setVendorId(VENDOR)
                        .setProductId(PRODUCT)
                        .setAssociatedDisplayId(display.displayId)
                        .build()
                )

        val pad = TouchpadView(this, mouse!!)
        val hint = label(getString(R.string.touchpad_hint), 0.45f)
        val buttons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button(R.string.touchpad_left, MotionEvent.BUTTON_PRIMARY), weighted())
                addView(View(this@TouchpadActivity), LinearLayout.LayoutParams(dp(2), MATCH_PARENT))
                addView(button(R.string.touchpad_right, MotionEvent.BUTTON_SECONDARY), weighted())
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    FrameLayout(this@TouchpadActivity).apply {
                        addView(hint)
                        addView(pad)
                    },
                    LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
                )
                addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, dp(88)))
            }
        )
    }

    override fun onDestroy() {
        mouse?.close()
        mouse = null
        super.onDestroy()
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()

    private fun weighted() = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)

    private fun label(text: String, alpha: Float) =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            this.alpha = alpha
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(dp(40), 0, dp(40), 0)
        }

    /** A button bar held down for as long as it is touched, so it can drag as well as click. */
    private fun button(label: Int, code: Int) =
        TextView(this).apply {
            text = getString(label)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 15f
            setBackgroundColor(0xFF1C1C1E.toInt())
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.setBackgroundColor(0xFF3A3A3C.toInt())
                        press(code, MotionEvent.ACTION_BUTTON_PRESS)
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        view.setBackgroundColor(0xFF1C1C1E.toInt())
                        press(code, MotionEvent.ACTION_BUTTON_RELEASE)
                    }
                }
                true
            }
        }

    private fun press(code: Int, action: Int) {
        mouse?.sendButtonEvent(
            VirtualMouseButtonEvent.Builder().setButtonCode(code).setAction(action).build()
        )
    }

    private companion object {
        // Google's vendor id, with a product id of PenguinOS's own.
        const val VENDOR = 0x18d1
        const val PRODUCT = 0x5044
    }
}
