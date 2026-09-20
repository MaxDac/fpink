package com.fpink.capture.data

import kotlinx.coroutines.flow.Flow

enum class ThemeMode(val storedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): ThemeMode =
            if (value == null) SYSTEM else entries.firstOrNull { it.storedValue == value }
                ?: throw IllegalArgumentException("Unsupported appearance preference")
    }
}

interface ThemeSettings {
    val themeMode: Flow<ThemeMode>
    suspend fun saveThemeMode(mode: ThemeMode)
}
