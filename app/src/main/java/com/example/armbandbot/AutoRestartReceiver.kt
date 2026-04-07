package com.heyheyon.armbandbot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

class AutoRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("AutoRestartReceiver", "onReceive 호출됨 / action=${intent?.action}")

        if (intent?.action != ACTION_RESTART_BOTS) {
            Log.d("AutoRestartReceiver", "액션 불일치로 종료")
            return
        }

        if (!hasRestorableBots(context)) {
            cancelWatchdog(context)
            Log.d("AutoRestartReceiver", "복구 대상 봇이 없어 watchdog 예약 취소")
            return
        }

        Log.d("AutoRestartReceiver", "백그라운드 브로드캐스트에서는 즉시 복구 대신 pending/watchdog만 유지")
        requestRestoreRunningBots(
            context = context,
            trigger = "AutoRestartReceiver",
            allowImmediateStart = false
        )
    }

    companion object {
        private const val WATCHDOG_REQUEST_CODE = 1001
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        const val ACTION_RESTART_BOTS = "com.heyheyon.armbandbot.ACTION_RESTART_BOTS"

        data class WatchdogScheduleResult(
            val scheduled: Boolean,
            val mode: String,
            val detail: String? = null
        )

        fun scheduleWatchdog(context: Context): WatchdogScheduleResult {
            return try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pendingIntent = buildPendingIntent(context)
                val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val canScheduleExact = alarmManager.canScheduleExactAlarms()
                    if (!canScheduleExact) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            pendingIntent
                        )
                        Log.w("AutoRestartReceiver", "watchdog exact alarm unavailable, fallback setAndAllowWhileIdle used")
                        return WatchdogScheduleResult(true, "inexact_fallback", "canScheduleExactAlarms=false")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                    Log.d("AutoRestartReceiver", "watchdog exact 60초 예약 완료")
                    WatchdogScheduleResult(true, "exact")
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                    Log.d("AutoRestartReceiver", "watchdog exact 60초 예약 완료")
                    WatchdogScheduleResult(true, "exact_legacy")
                }
            } catch (e: Exception) {
                Log.e("AutoRestartReceiver", "watchdog 예약 실패", e)
                WatchdogScheduleResult(false, "failed", e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
        }

        fun cancelWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = buildPendingIntent(context)
            alarmManager.cancel(pendingIntent)
            Log.d("AutoRestartReceiver", "watchdog 예약 취소 완료")
        }

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, AutoRestartReceiver::class.java).apply {
                action = ACTION_RESTART_BOTS
            }
            return PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
