package com.terryxtl.tododrop.transfer

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceName {
    /** 优先用用户在系统设置里起的设备名，取不到就用「厂商 型号」。 */
    fun get(context: Context): String {
        val custom = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim()
        if (!custom.isNullOrEmpty()) return custom
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return when {
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model".trim()
        }.ifEmpty { "Android" }
    }
}
