package com.yangyx.proxy.data.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val appName: String,
    val packageName: String,
    val iconDrawable: Drawable? = null,
    val isSystemApp: Boolean = false,
    val isSelected: Boolean = false
)
