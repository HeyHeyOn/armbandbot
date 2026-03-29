package com.heyheyon.armbandbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            when (intent?.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    val masterPref = context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
                    masterPref.edit()
                        .putBoolean("pending_restore_after_boot", true)
                        .apply()

                    Log.d("BootReceiver", "부팅/업데이트 감지: 봇 자동 복구를 대기 상태로 표시함")
                }
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "부팅 처리 실패", e)
        }
    }
}