package com.heyheyon.armbandbot

import android.content.SharedPreferences
import org.json.JSONArray

/** The scan configuration and its revision must describe the same settings save. */
internal fun snapshotScanPreferences(source: SharedPreferences): SharedPreferences {
    ensureRemoteListSettings(source)
    val values = source.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
    return object : SharedPreferences by source {
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun contains(key: String?) = values.containsKey(key)
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as Set<String>?)?.toMutableSet() ?: defValues?.toMutableSet()
        override fun edit(): SharedPreferences.Editor = error("Scan preferences are read-only")
    }
}

/** Only moderation inputs: runtime counters, UI order and credentials never invalidate scans. */
internal fun userFilterPolicyRevision(p: SharedPreferences): String {
    val fields = JSONArray().put(p.getBoolean("is_user_filter_mode", false))
    listOf("user_blacklist", "user_whitelist", "block_exempt_post_numbers").forEach { key ->
        fields.put(JSONArray(loadOrderedMultilineValues(p, key).distinct().sorted()))
    }
    val values = p.all
    listOf("", "user_").forEach { prefix ->
        listOf("block_process_mode", "delete_only_mode", "block_duration_hours",
            "block_reason_text", "delete_post_on_block").forEach { key ->
            fields.put(values[prefix + key] ?: org.json.JSONObject.NULL)
        }
    }
    fields.put(p.getBoolean("user_use_custom_action_config", false))
    return policyHash(fields.toString())
}
