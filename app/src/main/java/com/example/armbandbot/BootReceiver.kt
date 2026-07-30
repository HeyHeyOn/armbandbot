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
                    if (hasRestorableBots(context)) {
                        Log.d("BootReceiver", "부팅/업데이트 감지: fallback 예약 후 즉시 복구 요청")
                        val fallback = AutoRestartReceiver.scheduleWatchdog(context)
                        if (!fallback.scheduled) {
                            context.getSharedPreferences("bot_master", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("pending_restore_after_boot", true)
                                .apply()
                            ProcessExitDiagnostics.recordLifecycleEvent(
                                context,
                                "boot_fallback_failed",
                                fallback.detail,
                            )
                            AutoRestartReceiver.showRecoveryNotification(context)
                            Log.e("BootReceiver", "fallback 예약 실패, 사용자 알림과 즉시 복구를 함께 시도")
                        }
                        requestRestoreRunningBots(
                            context = context,
                            trigger = "BootReceiver",
                            allowImmediateStart = true,
                        )
                    } else {
                        clearPendingRestoreState(context)
                        AutoRestartReceiver.cancelWatchdog(context)
                        Log.d("BootReceiver", "부팅/업데이트 감지: 복구 대상 봇이 없어 대기 상태 정리")
                    }
                }
            }
        } catch (e: Exception) {
            ProcessExitDiagnostics.recordLifecycleEvent(
                context,
                "boot_restore_failed",
                "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
            )
            AutoRestartReceiver.showRecoveryNotification(context)
            Log.e("BootReceiver", "부팅 처리 실패", e)
        }
    }
}
