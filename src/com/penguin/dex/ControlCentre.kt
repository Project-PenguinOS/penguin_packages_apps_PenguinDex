/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.dex

import android.app.NotificationManager
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.text.TextUtils
import android.util.PathParser
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.hardware.input.InputManager
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A DeX desktop's Control Centre, as a Mac's: glass tiles under the status bar's corner, for the
 * phone's connections, modes, playing media, brightness and sound. It stands in for the Android
 * shade on a DeX screen.
 */
class ControlCentre(displayContext: Context, private val onClosed: () -> Unit) {

    private val context =
        displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG, null)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density

    private val root = Panel()
    private var shown = false
    private var media: MediaController? = null

    private val refreshers = mutableListOf<() -> Unit>()

    private val mediaCallback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()

            override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        }

    fun show() {
        if (shown) return
        shown = true
        build()
        val lp =
            WindowManager.LayoutParams(
                    dp(PANEL_DP + 20),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dp(0)
                    y = statusBarHeight() + dp(6)
                    title = "DeX Control Centre"
                    windowAnimations = 0
                }
        windowManager.addView(root, lp)
        // Glass goes on once the tiles have their sizes.
        root.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    applyGlass()
                }
            }
        )
        // Springs open from the status bar's corner.
        root.alpha = 0f
        root.scaleX = 0.9f
        root.scaleY = 0.9f
        root.pivotX = dp(PANEL_DP).toFloat()
        root.pivotY = 0f
        root.animate().alpha(1f).setDuration(ANIMATION_MS).start()
        root.animate().scaleX(1f).scaleY(1f).setDuration(380)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        refresh()
    }

    fun dismiss() {
        if (!shown) return
        shown = false
        media?.unregisterCallback(mediaCallback)
        root.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(ANIMATION_MS).withEndAction {
            if (root.isAttachedToWindow) windowManager.removeViewImmediate(root)
            onClosed()
        }.start()
    }

    private fun refresh() {
        if (shown) handler.post { refreshers.forEach { it() } }
    }

    private fun refreshSoon() = handler.postDelayed({ refresh() }, 700)

    /** The panel: a column of glass tiles over the blurred desktop. */
    private inner class Panel : LinearLayout(context) {
        init {
            orientation = VERTICAL
            isFocusableInTouchMode = true
            // Focused for Escape, without Android's grey focus highlight over the whole panel.
            defaultFocusHighlightEnabled = false
            clipChildren = false
            clipToPadding = false
            // Room for the tiles' shadows inside the window.
            setPadding(dp(10), dp(4), dp(10), dp(16))
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            requestFocus()
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismiss()
                return true
            }
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if ((event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK) &&
                event.action == KeyEvent.ACTION_UP
            ) {
                dismiss()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun build() {
        root.removeAllViews()
        refreshers.clear()
        val gap = dp(GAP_DP)

        // Settings, above the tiles.
        root.addView(
            TextView(context).apply {
                text = context.getString(R.string.cc_settings)
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                paint.isFakeBoldText = true
                setPadding(dp(18), dp(7), dp(18), dp(7))
                glass(this, dp(18).toFloat())
                setOnClickListener {
                    launch(Intent(Settings.ACTION_SETTINGS))
                }
            },
            LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = gap
                },
        )

        // Wi-Fi and Bluetooth beside what's playing.
        val top = LinearLayout(context)
        val connections = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        connections.addView(wifiTile(), rowParams(0))
        connections.addView(bluetoothTile(), rowParams(gap))
        top.addView(connections, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(
            mediaTile(),
            LinearLayout.LayoutParams(0, dp(PILL_DP) * 2 + gap, 1f).apply { leftMargin = gap },
        )
        root.addView(top, rowParams(0))

        // Airplane mode beside dark theme and a screenshot.
        val middle = LinearLayout(context)
        middle.addView(airplaneTile(), LinearLayout.LayoutParams(0, dp(PILL_DP), 1f))
        middle.addView(darkTile(), roundParams(gap))
        middle.addView(screenshotTile(), roundParams(gap))
        root.addView(middle, rowParams(gap))

        // The torch and battery saver beside Do Not Disturb.
        val modes = LinearLayout(context)
        modes.addView(torchTile(), roundParams(0))
        modes.addView(saverTile(), roundParams(gap))
        modes.addView(dndTile(), LinearLayout.LayoutParams(0, dp(PILL_DP), 1f).apply { leftMargin = gap })
        root.addView(modes, rowParams(gap))

        root.addView(brightnessTile(), rowParams(gap))
        root.addView(soundTile(), rowParams(gap))
    }

    private fun rowParams(top: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = top }

    private fun roundParams(left: Int) =
        LinearLayout.LayoutParams(dp(PILL_DP), dp(PILL_DP)).apply { leftMargin = left }

    // Tiles

    private fun wifiTile(): View {
        val wifi = context.getSystemService(WifiManager::class.java)
        return pill(Icons.WIFI, R.string.cc_wifi, { wifi.isWifiEnabled }, {
            @Suppress("DEPRECATION")
            val ssid = wifi.connectionInfo?.ssid?.trim('"')
            when {
                !wifi.isWifiEnabled -> context.getString(R.string.cc_off)
                ssid.isNullOrEmpty() || ssid == WifiManager.UNKNOWN_SSID.trim('<', '>') ||
                    ssid.startsWith("<") -> context.getString(R.string.cc_on)
                else -> ssid
            }
        }) {
            @Suppress("DEPRECATION")
            wifi.setWifiEnabled(!wifi.isWifiEnabled)
        }
    }

    private fun bluetoothTile(): View {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return pill(Icons.BLUETOOTH, R.string.cc_bluetooth, { adapter?.isEnabled == true }, {
            context.getString(if (adapter?.isEnabled == true) R.string.cc_on else R.string.cc_off)
        }) {
            if (adapter?.isEnabled == true) adapter.disable() else adapter?.enable()
        }
    }

    private fun airplaneTile(): View {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun on() = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        return pill(Icons.AIRPLANE, R.string.cc_airplane, ::on, {
            context.getString(if (on()) R.string.cc_on else R.string.cc_off)
        }) {
            connectivity.setAirplaneMode(!on())
        }
    }

    private fun dndTile(): View {
        val notifications = context.getSystemService(NotificationManager::class.java)
        fun on() = notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        return pill(Icons.MOON, R.string.cc_dnd, ::on, {
            context.getString(if (on()) R.string.cc_on else R.string.cc_off)
        }) {
            notifications.setInterruptionFilter(
                if (on()) NotificationManager.INTERRUPTION_FILTER_ALL
                else NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
        }
    }

    private fun darkTile(): View {
        val modes = context.getSystemService(UiModeManager::class.java)
        fun on() = modes.nightMode == UiModeManager.MODE_NIGHT_YES
        return round(Icons.CONTRAST, R.string.cc_dark, ::on) {
            modes.nightMode = if (on()) UiModeManager.MODE_NIGHT_NO else UiModeManager.MODE_NIGHT_YES
        }
    }

    private fun screenshotTile(): View =
        round(Icons.SCREENSHOT, R.string.cc_screenshot, { false }) {
            // The panel closes first, so the shot is of the desktop under it.
            val displayId = context.display?.displayId ?: Display.DEFAULT_DISPLAY
            dismiss()
            handler.postDelayed({ screenshot(displayId) }, ANIMATION_MS + 150)
        }

    private var torchOn = false

    private fun torchTile(): View {
        val cameras = context.getSystemService(CameraManager::class.java)
        val camera =
            runCatching {
                    cameras.cameraIdList.firstOrNull {
                        cameras.getCameraCharacteristics(it)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                }
                .getOrNull()
        return round(Icons.TORCH, R.string.cc_torch, { torchOn }) {
            camera ?: return@round
            torchOn = !torchOn
            runCatching { cameras.setTorchMode(camera, torchOn) }
        }
    }

    private fun saverTile(): View {
        val power = context.getSystemService(PowerManager::class.java)
        return round(Icons.SAVER, R.string.cc_saver, { power.isPowerSaveMode }) {
            power.setPowerSaveModeEnabled(!power.isPowerSaveMode)
        }
    }

    private fun mediaTile(): View {
        val sessions = context.getSystemService(MediaSessionManager::class.java)
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(16), dp(16), dp(10))
                glass(this, dp(CARD_RADIUS_DP).toFloat())
            }
        val art =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0x33FFFFFF)
                }
            }
        card.addView(art, LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.START })
        val title =
            TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        card.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
            },
        )
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
        val previous = iconButton(Icons.PREVIOUS)
        val play = iconButton(Icons.PLAY)
        val next = iconButton(Icons.NEXT)
        listOf(previous, play, next).forEach {
            controls.addView(it, LinearLayout.LayoutParams(0, dp(30), 1f))
        }
        card.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))

        previous.setOnClickListener { media?.transportControls?.skipToPrevious() }
        next.setOnClickListener { media?.transportControls?.skipToNext() }
        play.setOnClickListener {
            val controller = media ?: return@setOnClickListener
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
        }

        refreshers += {
            val controller =
                runCatching { sessions.getActiveSessions(null) }.getOrNull()?.firstOrNull()
            if (controller?.sessionToken != media?.sessionToken) {
                media?.unregisterCallback(mediaCallback)
                media = controller
                controller?.registerCallback(mediaCallback, handler)
            }
            val metadata = controller?.metadata
            val bitmap: Bitmap? =
                metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (bitmap != null) {
                art.setImageBitmap(bitmap)
            } else {
                art.setImageDrawable(IconDrawable(Icons.MUSIC, 0x99FFFFFF.toInt(), 0.5f))
            }
            title.text =
                metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: context.getString(R.string.cc_not_playing)
            val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
            play.setImageDrawable(IconDrawable(if (playing) Icons.PAUSE else Icons.PLAY, Color.WHITE))
        }
        return card
    }

    private fun brightnessTile(): View {
        val displays = context.getSystemService(DisplayManager::class.java)
        val phone = displays.getDisplay(Display.DEFAULT_DISPLAY)
        return slider(R.string.cc_display, Icons.SUN, {
            val info = phone?.brightnessInfo
            if (info == null || info.brightnessMaximum <= info.brightnessMinimum) 0f
            else {
                val linear =
                    (info.brightness - info.brightnessMinimum) /
                        (info.brightnessMaximum - info.brightnessMinimum)
                linear.coerceIn(0f, 1f).pow(1f / GAMMA)
            }
        }) { value ->
            val info = phone?.brightnessInfo ?: return@slider
            val linear = value.pow(GAMMA)
            displays.setBrightness(
                Display.DEFAULT_DISPLAY,
                info.brightnessMinimum + linear * (info.brightnessMaximum - info.brightnessMinimum),
            )
        }
    }

    private fun soundTile(): View {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return slider(R.string.cc_sound, Icons.VOLUME, {
            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        }) { value ->
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt(), 0)
        }
    }

    // Building blocks

    /** A pill: a round icon, lit while on, beside a name and its state. */
    private fun pill(
        icon: String,
        name: Int,
        on: () -> Boolean,
        state: () -> String,
        toggle: () -> Unit,
    ): View {
        val circle = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER }
        val label =
            TextView(context).apply {
                text = context.getString(name)
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                paint.isFakeBoldText = true
                setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x40000000)
            }
        val subtitle =
            TextView(context).apply {
                setTextColor(0xB3FFFFFF.toInt())
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(subtitle)
            }
        val pill =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(14), 0)
                glass(this, dp(PILL_DP / 2).toFloat())
                addView(circle, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(
                    texts,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(10)
                    },
                )
                minimumHeight = dp(PILL_DP)
                setOnClickListener {
                    toggle()
                    refreshSoon()
                    refresh()
                }
            }
        val tint = if (icon == Icons.MOON) PURPLE else ACCENT
        refreshers += {
            val active = on()
            circle.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (active) Color.WHITE else 0x2EFFFFFF)
                }
            circle.setImageDrawable(IconDrawable(icon, if (active) tint else Color.WHITE, 0.5f))
            subtitle.text = state()
        }
        return pill
    }

    /** A round button, lit while on. */
    private fun round(icon: String, name: Int, on: () -> Boolean, toggle: () -> Unit): View {
        val glyph = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER }
        val button =
            FrameLayout(context).apply {
                contentDescription = context.getString(name)
                tooltipText = context.getString(name)
                addView(glyph, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                setOnClickListener {
                    toggle()
                    refreshSoon()
                    refresh()
                }
                glass(this, dp(PILL_DP / 2).toFloat())
            }
        refreshers += {
            val active = on()
            // Lit, the glass fills with the accent, as a Mac's buttons do.
            glyph.background =
                if (active) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ACCENT)
                    }
                } else {
                    null
                }
            glyph.setImageDrawable(IconDrawable(icon, Color.WHITE, 0.4f))
        }
        return button
    }

    /** A titled slider card, as the Mac's Display and Sound. */
    private fun slider(name: Int, icon: String, read: () -> Float, write: (Float) -> Unit): View {
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                glass(this, dp(CARD_RADIUS_DP).toFloat())
            }
        card.addView(
            TextView(context).apply {
                text = context.getString(name)
                setTextColor(Color.WHITE)
                textSize = 15f
                paint.isFakeBoldText = true
            }
        )
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(
            ImageView(context).apply { setImageDrawable(IconDrawable(icon, Color.WHITE, 0.7f)) },
            LinearLayout.LayoutParams(dp(18), dp(18)),
        )
        val bar =
            SeekBar(context).apply {
                max = 1000
                progressDrawable = track()
                thumb = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setSize(dp(14), dp(14))
                }
                splitTrack = false
                setPadding(dp(10), 0, dp(10), 0)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                            if (fromUser) write(progress / 1000f)
                        }

                        override fun onStartTrackingTouch(bar: SeekBar) {}

                        override fun onStopTrackingTouch(bar: SeekBar) {}
                    }
                )
            }
        row.addView(bar, LinearLayout.LayoutParams(0, dp(28), 1f))
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)).apply {
            topMargin = dp(4)
        })
        refreshers += { bar.progress = (read() * 1000).roundToInt() }
        return card
    }

    private fun track(): Drawable {
        val back = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(0x40FFFFFF)
            setSize(0, dp(7))
        }
        val fill = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(Color.WHITE)
            setSize(0, dp(7))
        }
        return LayerDrawable(arrayOf(back, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)))
            .apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
                setLayerGravity(0, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                setLayerGravity(1, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
                setLayerHeight(0, dp(7))
                setLayerHeight(1, dp(7))
            }
    }

    private fun iconButton(icon: String) =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            setImageDrawable(IconDrawable(icon, Color.WHITE, 0.8f))
        }

    private val glassTiles = mutableListOf<Pair<View, Float>>()

    /**
     * Makes [view] a glass tile: the desktop blurred behind it, a pale sheen over that, a light
     * edge brightest at the top, a soft shadow, and a give under the finger.
     */
    private fun glass(view: View, radius: Float): View {
        view.background = GradientDrawable().apply {
            cornerRadius = radius
            setColor(GLASS_FALLBACK)
        }
        view.elevation = dp(6).toFloat()
        view.outlineProvider =
            object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                    outline.alpha = 0.35f
                }
            }
        springy(view)
        glassTiles += view to radius
        return view
    }

    /** Swaps each tile's stand-in for real glass, which needs the window to blur through. */
    private fun applyGlass() {
        val viewRoot = root.viewRootImpl ?: return
        unclip(root)
        val blurs = windowManager.isCrossWindowBlurEnabled
        for ((view, radius) in glassTiles) {
            val sheen =
                GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(SHEEN_TOP, SHEEN_BOTTOM),
                    )
                    .apply { cornerRadius = radius }
            val layers = mutableListOf<Drawable>()
            if (blurs) {
                layers +=
                    viewRoot.createBackgroundBlurDrawable().apply {
                        setBlurRadius(dp(BLUR_DP))
                        setCornerRadius(radius)
                        setColor(TINT)
                    }
            } else {
                layers += GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(GLASS_FALLBACK)
                }
            }
            layers += sheen
            layers += GlassEdge(radius, dp(1).toFloat())
            val glass = LayerDrawable(layers.toTypedArray())
            view.background = glass
            // A blur with no size yet blurs the whole window, so size it now and keep it sized.
            glass.setBounds(0, 0, view.width, view.height)
            view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.background?.setBounds(0, 0, v.width, v.height)
            }
            view.invalidate()
        }
    }

    /** Lets the tiles' shadows spill past the rows that hold them. */
    private fun unclip(group: ViewGroup) {
        group.clipChildren = false
        group.clipToPadding = false
        for (i in 0 until group.childCount) (group.getChildAt(i) as? ViewGroup)?.let(::unclip)
    }

    /** Sinks a little under the finger and springs back, as glass on a Mac does. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun springy(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    /** A fine light edge, bright along the top and fading down the sides, as glass catches light. */
    private class GlassEdge(private val radius: Float, private val width: Float) : Drawable() {
        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = width
            }
        private val rect = android.graphics.RectF()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            paint.shader =
                android.graphics.LinearGradient(
                    0f,
                    bounds.top.toFloat(),
                    0f,
                    bounds.bottom.toFloat(),
                    intArrayOf(0x8CFFFFFF.toInt(), 0x1FFFFFFF, 0x33FFFFFF),
                    floatArrayOf(0f, 0.55f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
        }

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            rect.inset(width / 2, width / 2)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun launch(intent: Intent) {
        val displayId = context.display?.displayId ?: Display.DEFAULT_DISPLAY
        dismiss()
        context.startActivityAsUser(
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
            UserHandle.CURRENT,
        )
    }

    /** Takes a screenshot of [displayId] as its keyboard shortcut would. */
    private fun screenshot(displayId: Int) {
        val input = context.getSystemService(InputManager::class.java)
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event =
                KeyEvent(now, now, action, KeyEvent.KEYCODE_SYSRQ, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            event.displayId = displayId
            input.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
        }
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) context.resources.getDimensionPixelSize(id) else dp(28)
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics)
            .roundToInt()

    /** An icon drawn from a 24 by 24 path, at [scale] of the space it's given. */
    private class IconDrawable(data: String, color: Int, private val scale: Float = 0.6f) : Drawable() {
        private val source = PathParser.createPathFromPathData(data)
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val matrix = Matrix()

        override fun draw(canvas: Canvas) {
            val size = minOf(bounds.width(), bounds.height()) * scale
            val factor = size / 24f
            matrix.setScale(factor, factor)
            matrix.postTranslate(bounds.exactCenterX() - size / 2, bounds.exactCenterY() - size / 2)
            source.transform(matrix, path)
            canvas.drawPath(path, paint)
        }

        override fun getIntrinsicWidth() = -1

        override fun getIntrinsicHeight() = -1

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val PANEL_DP = 360
        const val PILL_DP = 68
        const val GAP_DP = 12
        const val CARD_RADIUS_DP = 28
        const val ANIMATION_MS = 180L
        const val GAMMA = 2.2f
        const val BLUR_DP = 34
        // The tint over the blur keeps white text legible on a light desktop.
        const val TINT = 0x4D1C1C24
        const val SHEEN_TOP = 0x33FFFFFF
        const val SHEEN_BOTTOM = 0x0DFFFFFF
        const val GLASS_FALLBACK = 0xB32C2C34.toInt()
        const val ACCENT = 0xFF0A84FF.toInt()
        const val PURPLE = 0xFF7D5BFF.toInt()
    }
}

/** Material icons, as 24 by 24 path data. */
private object Icons {
    const val WIFI =
        "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z"
    const val BLUETOOTH =
        "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z"
    const val AIRPLANE =
        "M21,16v-2l-8,-5V3.5C13,2.67 12.33,2 11.5,2S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1 3.5,1v-1.5L13,19v-5.5l8,2.5z"
    const val CONTRAST =
        "M12,22C17.52,22 22,17.52 22,12S17.52,2 12,2 2,6.48 2,12s4.48,10 10,10zM13,4.07c3.94,0.49 7,3.85 7,7.93s-3.05,7.44 -7,7.93V4.07z"
    const val SCREENSHOT =
        "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14zM9.5,8.5H12V7H8v4h1.5V8.5zM12,17h4v-4h-1.5v2.5H12V17z"
    const val TORCH =
        "M6,2v6l2,3v11h8V11l2,-3V2H6zM12,15.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5zM16,6H8V4h8v2z"
    const val SAVER =
        "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4zM15,13h-2v2h-2v-2H9v-2h2V9h2v2h2v2z"
    const val MOON =
        "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9 9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36 -0.98,1.37 -2.58,2.26 -4.4,2.26 -2.98,0 -5.4,-2.42 -5.4,-5.4 0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z"
    const val PLAY = "M8,5v14l11,-7z"
    const val PAUSE = "M6,19h4V5H6v14zM14,5v14h4V5h-4z"
    const val PREVIOUS = "M6,6h2v12H6zM9.5,12l8.5,6V6z"
    const val NEXT = "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z"
    const val SUN =
        "M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5zM2,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1H2c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM20,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1h-2c-0.55,0 -1,0.45 -1,1s0.45,1 1,1zM11,2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1V2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1zM11,20v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2c0,-0.55 -0.45,-1 -1,-1 -0.55,0 -1,0.45 -1,1z"
    const val VOLUME =
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
    const val MUSIC =
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z"
}
