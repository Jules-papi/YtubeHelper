package com.example.youtubeoverlay.util

import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityUtils {
    fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        var accessibilityEnabled = 0
        val serviceName = context.packageName + "/" + service.canonicalName
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            e.printStackTrace()
        }

        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                val split = TextUtils.SimpleStringSplitter(':')
                split.setString(settingValue)
                while (split.hasNext()) {
                    val accessibilityService = split.next()
                    if (accessibilityService.equals(serviceName, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}
