/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.dex

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManagerGlobal

/** The connected screens running a DeX desktop, as the window manager decides them. */
fun dexDisplays(context: Context): List<Display> {
    val wm = WindowManagerGlobal.getWindowManagerService() ?: return emptyList()
    return context.getSystemService(DisplayManager::class.java).displays.filter {
        it.displayId != Display.DEFAULT_DISPLAY &&
            runCatching { wm.isEligibleForDesktopMode(it.displayId) }.getOrDefault(false)
    }
}
