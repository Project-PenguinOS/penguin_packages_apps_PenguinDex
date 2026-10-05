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
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display

/** Offers the touchpad for as long as a DeX desktop is on a connected screen. */
class DexService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifications: NotificationManager

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
        update()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun update() {
        // The window manager settles a new screen's desktop a moment after it is added.
        handler.postDelayed({
            if (dexDisplays(this).isEmpty()) {
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

    private companion object {
        const val CHANNEL = "dex"
        const val NOTIFICATION = 1
    }
}
