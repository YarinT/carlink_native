package com.carlink.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.carlink.BuildConfig

enum class SettingsTab(
    val title: String,
    val icon: ImageVector,
) {
    PHONES("Phones", Icons.Default.PhoneAndroid),
    CONTROL("Control", Icons.Default.Settings),
    LOGS("Logs", Icons.AutoMirrored.Filled.Article),
    ;

    companion object {
        val visible: List<SettingsTab>
            get() =
                entries.filter { tab ->
                    when (tab) {
                        LOGS -> !BuildConfig.DEBUG
                        else -> true
                    }
                }
    }
}
