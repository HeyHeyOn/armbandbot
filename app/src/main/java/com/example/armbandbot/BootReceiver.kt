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
                    val hasRestorableBot = hasRestorableBots(context)

                    masterPref.edit()
                        .putBoolean("pending_restore_after_boot", hasRestorableBot)
                        .apply()

                    if (hasRestorableBot) {
                        Log.d("BootReceiver", "부팅/업데이트 감지: 복구 대상 봇이 있어 즉시 복구를 시도함")
                        restoreRunningBots(context)
                    } else {
                        Log.d("BootReceiver", "부팅/업데이트 감지: 복구 대상 봇이 없어 대기 상태만 정리함")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "부팅 처리 실패", e)
        }
    }
}