package com.heyheyon.armbandbot

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Service/login changes and a different bot identity must reach the visible switch. */
@Composable
internal fun rememberBotRunning(preferences: SharedPreferences): MutableState<Boolean> {
    val running = remember(preferences) {
        mutableStateOf(preferences.getBoolean("is_running", false))
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "is_running") {
                running.value = preferences.getBoolean("is_running", false)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        // Close the gap between composition's first read and listener registration.
        running.value = preferences.getBoolean("is_running", false)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return running
}

@Composable
internal fun rememberRunningBotCount(context: Context, botIds: List<String>): Int {
    val preferences = remember(context, botIds) {
        botIds.map { context.getSharedPreferences("bot_prefs_$it", Context.MODE_PRIVATE) }
    }
    var count by remember(preferences) {
        mutableIntStateOf(preferences.count { it.getBoolean("is_running", false) })
    }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "is_running") {
                count = preferences.count { it.getBoolean("is_running", false) }
            }
        }
        preferences.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        count = preferences.count { it.getBoolean("is_running", false) }
        onDispose { preferences.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }
    return count
}
