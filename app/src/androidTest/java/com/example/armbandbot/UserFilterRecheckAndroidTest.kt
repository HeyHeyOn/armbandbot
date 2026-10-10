package com.heyheyon.armbandbot

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class UserFilterRecheckAndroidTest {
    @Test fun realPreferencesAndServiceConfigShareAnImmutableCycle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val p = context.getSharedPreferences("bot_prefs_recheck_offline", Context.MODE_PRIVATE)
        val service = BotService()
        val load = BotService::class.java.getDeclaredMethod("loadBotConfig", String::class.java, SharedPreferences::class.java).apply { isAccessible=true }
        fun field(config: Any, name: String) = config.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(config)
        try {
            p.edit().clear().commit()
            persistOrderedMultilineText(p, "user_blacklist", "before")
            val old = snapshotScanPreferences(p)
            val state = AutomationRecheckState(); val key = PostKey("MI", "offline", "1")
            state.update(automationPolicyRevision(old)); state.mark(key)
            persistOrderedMultilineText(p, "user_blacklist", "after")
            p.edit().putBoolean("is_user_filter_mode", true).commit()
            val priorConfig = load.invoke(service,"recheck_offline",old)!!
            assertEquals(false,field(priorConfig,"isUserFilterMode"))
            assertEquals(listOf("before"),field(priorConfig,"userBlacklist"))
            val current = snapshotScanPreferences(p)
            val nextConfig = load.invoke(service,"recheck_offline",current)!!
            assertEquals(true,field(nextConfig,"isUserFilterMode"))
            assertEquals(listOf("after"),field(nextConfig,"userBlacklist"))
            state.update(automationPolicyRevision(current)); assertTrue(state.needs(key))
            state.mark(key); assertFalse(state.needs(key))
        } finally { p.edit().clear().commit() }
    }
}
