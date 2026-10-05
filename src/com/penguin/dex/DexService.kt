/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.dex

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Display

/** Offers the touchpad for as long as a DeX desktop is on a connected screen. */
class DexService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifications: NotificationManager

    private val touchpadObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = update()
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = update()

            override fun onDisplayRemoved(displayId: Int) = update()

            override fun onDisplayChanged(displayId: Int) {}
        }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_dex), NotificationManager.IMPORTANCE_LOW)
        )
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(TOUCHPAD),
            false,
            touchpadObserver,
        )
        update()
    }

    private val controlCentres = mutableMapOf<Int, ControlCentre>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONTROL_CENTRE) {
            toggleControlCentre(intent.getIntExtra(EXTRA_DISPLAY, Display.INVALID_DISPLAY))
        }
        return START_STICKY
    }

    /** Opens the Control Centre on a DeX screen, or closes it if it's open there. */
    private fun toggleControlCentre(displayId: Int) {
        controlCentres[displayId]?.let {
            it.dismiss()
            return
        }
        val display = getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return
        val centre = ControlCentre(createDisplayContext(display)) { controlCentres.remove(displayId) }
        controlCentres[displayId] = centre
        centre.show()
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        contentResolver.unregisterContentObserver(touchpadObserver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun update() {
        // The window manager settles a new screen's desktop a moment after it is added.
        handler.postDelayed({
            // Penguin Laboratory can turn the touchpad off.
            val wanted = Settings.Secure.getInt(contentResolver, TOUCHPAD, 1) != 0
            if (!wanted || dexDisplays(this).isEmpty()) {
                notifications.cancel(NOTIFICATION)
                return@postDelayed
            }
            val touchpad =
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, TouchpadActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                    // Opened from the shade on the DeX screen, the touchpad still belongs on the
                    // phone.
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
                )
            notifications.notify(
                NOTIFICATION,
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_touchpad)
                    .setContentTitle(getString(R.string.dex_running_title))
                    .setContentText(getString(R.string.dex_running_text))
                    .setContentIntent(touchpad)
                    .setOngoing(true)
                    .setLocalOnly(true)
                    .build(),
            )
        }, 600)
    }

    companion object {
        /** SystemUI's request, from a DeX screen's status bar, for its Control Centre. */
        const val ACTION_CONTROL_CENTRE = "com.penguin.dex.action.CONTROL_CENTRE"
        const val EXTRA_DISPLAY = "display"
        private const val TOUCHPAD = "penguin_dex_touchpad"
        private const val CHANNEL = "dex"
        private const val NOTIFICATION = 1
    }
}
