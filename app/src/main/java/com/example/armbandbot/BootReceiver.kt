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
                    val hasRestorableBot = hasRestorableBots(context)

                    if (hasRestorableBot) {
                        Log.d("BootReceiver", "부팅/업데이트 감지: 즉시 복구 대신 pending/watchdog로 보수 처리")
                        requestRestoreRunningBots(
                            context = context,
                            trigger = "BootReceiver",
                            allowImmediateStart = false
                        )
                    } else {
                        context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("pending_restore_after_boot", false)
                            .apply()
                        AutoRestartReceiver.cancelWatchdog(context)
                        Log.d("BootReceiver", "부팅/업데이트 감지: 복구 대상 봇이 없어 대기 상태만 정리함")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "부팅 처리 실패", e)
        }
    }
}