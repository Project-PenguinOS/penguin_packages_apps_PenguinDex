/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.dex.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** An app as the Launchpad shows it; [tag] is the host's own record of it. */
class LaunchpadApp(val label: CharSequence, val icon: Drawable, val tag: Any?)

/**
 * A DeX desktop's app drawer, as a Mac's Launchpad: every app over the dimmed desktop, in pages
 * of large icons a swipe or a scroll apart, with a search field above them.
 */
class Launchpad(context: Context) : FrameLayout(context) {

    /** Called when an app is chosen, with the view it was chosen from. */
    var onLaunch: ((LaunchpadApp, View) -> Unit)? = null

    /** Called when the Launchpad should close: a tap on the desktop, or Escape. */
    var onDismiss: (() -> Unit)? = null

    private var apps: List<LaunchpadApp> = emptyList()
    private var shown: List<LaunchpadApp> = emptyList()
    private val content = LinearLayout(context)
    private val search = EditText(context)
    private val pager = Pager()
    private val dots = LinearLayout(context)

    init {
        setBackgroundColor(SCRIM)
        isFocusableInTouchMode = true
        setOnClickListener { onDismiss?.invoke() }

        content.orientation = LinearLayout.VERTICAL
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        search.apply {
            hint = context.getString(R.string.penguin_dex_launchpad_search)
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(10f)
                    setColor(0x33FFFFFF)
                }
            setPadding(dp(12), 0, dp(12), 0)
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

                    override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
                }
            )
            // Return opens the first app found, as on a Mac.
            setOnEditorActionListener { view, _, _ ->
                shown.firstOrNull()?.let { onLaunch?.invoke(it, view) }
                true
            }
        }
        content.addView(
            search,
            LinearLayout.LayoutParams(dp(260), dp(34)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(32)
            },
        )
        content.addView(
            pager,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(24)
            },
        )
        dots.gravity = Gravity.CENTER
        content.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28)))
    }

    /** Shows [apps], sorted by name. */
    fun setApps(apps: List<LaunchpadApp>) {
        this.apps = apps.sortedBy { it.label.toString().lowercase() }
        filter(search.text?.toString().orEmpty())
    }

    /** Keeps clear of the status bar above and the dock below. */
    fun setInsets(top: Int, bottom: Int) {
        content.setPadding(0, top, 0, bottom)
    }

    fun animateIn() {
        alpha = 0f
        content.scaleX = 1.08f
        content.scaleY = 1.08f
        animate().alpha(1f).setDuration(ANIMATION_MS).start()
        content.animate().scaleX(1f).scaleY(1f).setDuration(ANIMATION_MS)
            .setInterpolator(DecelerateInterpolator()).start()
        search.requestFocus()
    }

    fun animateOut(end: Runnable) {
        animate().alpha(0f).setDuration(ANIMATION_MS).withEndAction(end).start()
        content.animate().scaleX(1.08f).scaleY(1.08f).setDuration(ANIMATION_MS).start()
    }

    // The desktop blurs behind the Launchpad while it's open, as a Mac's does.
    private var savedFlags = 0
    private var savedBlur = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val window = rootView
        val lp = window.layoutParams as? WindowManager.LayoutParams ?: return
        savedFlags = lp.flags
        savedBlur = lp.blurBehindRadius
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        lp.blurBehindRadius = dp(BLUR_DP)
        context.getSystemService(WindowManager::class.java).updateViewLayout(window, lp)
    }

    override fun onDetachedFromWindow() {
        val window = rootView
        (window.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
            lp.flags = savedFlags
            lp.blurBehindRadius = savedBlur
            if (window.isAttachedToWindow) {
                context.getSystemService(WindowManager::class.java).updateViewLayout(window, lp)
            }
        }
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.action == KeyEvent.ACTION_UP) {
            onDismiss?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun filter(query: String) {
        shown =
            if (query.isBlank()) apps
            else apps.filter { it.label.toString().contains(query.trim(), ignoreCase = true) }
        pager.setPages(shown.chunked(COLUMNS * ROWS).ifEmpty { listOf(emptyList()) })
    }

    private fun updateDots(current: Int, count: Int) {
        dots.removeAllViews()
        if (count < 2) return
        repeat(count) { index ->
            dots.addView(
                View(context).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(if (index == current) Color.WHITE else 0x66FFFFFF)
                        }
                },
                LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    leftMargin = dp(5)
                    rightMargin = dp(5)
                },
            )
        }
    }

    private fun page(apps: List<LaunchpadApp>): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), 0, dp(48), 0)
            for (row in 0 until ROWS) {
                val line = LinearLayout(context)
                for (column in 0 until COLUMNS) {
                    val app = apps.getOrNull(row * COLUMNS + column)
                    line.addView(
                        if (app != null) cell(app) else View(context),
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                    )
                }
                addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }

    private fun cell(app: LaunchpadApp): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            // A Mac shows where the pointer is over the apps.
            background =
                StateListDrawable().apply {
                    val lit =
                        GradientDrawable().apply {
                            cornerRadius = dp(14f)
                            setColor(0x22FFFFFF)
                        }
                    addState(intArrayOf(android.R.attr.state_hovered), lit)
                    addState(intArrayOf(android.R.attr.state_pressed), lit)
                    addState(intArrayOf(android.R.attr.state_focused), lit)
                }
            setOnClickListener { onLaunch?.invoke(app, it) }
            addView(
                ImageView(context).apply { setImageDrawable(app.icon) },
                LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)),
            )
            addView(
                TextView(context).apply {
                    text = app.label
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setShadowLayer(dp(3f), 0f, dp(1f), 0x99000000.toInt())
                },
                LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                },
            )
        }

    private fun dp(value: Int) = dp(value.toFloat()).roundToInt()

    private fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    /** The pages side by side, dragged or scrolled a page at a time. */
    private inner class Pager : ViewGroup(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var current = 0
        private var downX = 0f
        private var downY = 0f
        private var startScroll = 0
        private var dragging = false
        private var velocity: VelocityTracker? = null
        private var settling: ValueAnimator? = null
        private var lastWheel = 0L

        fun setPages(pages: List<List<LaunchpadApp>>) {
            removeAllViews()
            pages.forEach { addView(page(it)) }
            current = 0
            scrollTo(0, 0)
            updateDots(0, pages.size)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            for (i in 0 until childCount) {
                getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            for (i in 0 until childCount) getChildAt(i).layout(i * width, 0, (i + 1) * width, b - t)
            scrollTo(current * width, 0)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(event)
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    if (abs(dx) > slop && abs(dx) > abs(event.y - downY)) dragging = true
                }
            }
            return dragging
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (velocity == null) velocity = VelocityTracker.obtain()
            velocity?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(event)
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    if (!dragging && abs(dx) > slop) dragging = true
                    if (dragging) {
                        val max = (childCount - 1) * width
                        scrollTo((startScroll - dx).roundToInt().coerceIn(-width / 4, max + width / 4), 0)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        velocity?.computeCurrentVelocity(1000)
                        val fling = velocity?.xVelocity ?: 0f
                        val target =
                            when {
                                fling < -FLING -> current + 1
                                fling > FLING -> current - 1
                                else -> (scrollX.toFloat() / width).roundToInt()
                            }
                        settle(target)
                    } else {
                        // A tap between the apps closes the Launchpad, as on a Mac.
                        onDismiss?.invoke()
                    }
                    end()
                }
                MotionEvent.ACTION_CANCEL -> {
                    settle(current)
                    end()
                }
            }
            return true
        }

        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_SCROLL) return super.onGenericMotionEvent(event)
            // A wheel or a two finger swipe turns the page, once per gesture.
            if (event.eventTime - lastWheel < WHEEL_MS) return true
            val amount =
                event.getAxisValue(MotionEvent.AXIS_HSCROLL).takeIf { it != 0f }
                    ?: -event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (amount == 0f) return false
            lastWheel = event.eventTime
            settle(current + if (amount > 0) 1 else -1)
            return true
        }

        private fun begin(event: MotionEvent) {
            settling?.cancel()
            downX = event.x
            downY = event.y
            startScroll = scrollX
            dragging = false
        }

        private fun end() {
            dragging = false
            velocity?.recycle()
            velocity = null
        }

        private fun settle(page: Int) {
            current = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
            updateDots(current, childCount)
            settling?.cancel()
            settling =
                ValueAnimator.ofInt(scrollX, current * width).apply {
                    duration = ANIMATION_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                    start()
                }
        }
    }

    private companion object {
        const val COLUMNS = 7
        const val ROWS = 5
        const val ICON_DP = 64
        const val ANIMATION_MS = 220L
        const val WHEEL_MS = 350L
        const val FLING = 900f
        const val SCRIM = 0x80121216.toInt()
        const val BLUR_DP = 40
    }
}
