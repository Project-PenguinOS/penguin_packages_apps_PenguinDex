/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.penguin.dex.launcher

import android.content.Context
import android.provider.Settings

/** The DeX features Penguin Laboratory turns on and off, as the launcher's hooks ask for them. */
object DexFeatures {
    @JvmStatic fun dock(context: Context) = on(context, "penguin_dex_dock")

    @JvmStatic fun launchpad(context: Context) = on(context, "penguin_dex_launchpad")

    private fun on(context: Context, key: String) =
        Settings.Secure.getInt(context.contentResolver, key, 1) != 0
}
