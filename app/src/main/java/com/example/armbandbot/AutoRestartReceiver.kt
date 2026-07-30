package com.heyheyon.armbandbot

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

class AutoRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("AutoRestartReceiver", "onReceive 호출됨 / action=${intent?.action}")

        if (intent?.action != ACTION_RESTART_BOTS) {
            Log.d("AutoRestartReceiver", "액션 불일치로 종료")
            return
        }

        val decision = decideRecoveryAction(
            hasRestorableBots = hasRestorableBots(context),
            serviceAlive = BotService.hasAllRestorableBotsEnteredRunLoop(context),
        )

        when (decision) {
            RecoveryDecision.CANCEL -> {
                cancelWatchdog(context)
                clearPendingRestoreState(context)
                Log.d("AutoRestartReceiver", "복구 대상 봇이 없어 watchdog 예약 취소")
            }

            RecoveryDecision.REARM_ONLY -> {
                cancelRecoveryNotification(context)
                scheduleWatchdog(context)
                Log.d("AutoRestartReceiver", "서비스가 실행 중이므로 watchdog만 재예약")
            }

            RecoveryDecision.DEFER_AND_REARM -> {
                Log.w("AutoRestartReceiver", "복구 대상은 있으나 서비스가 없어 사용자 복구 대기")
                requestRestoreRunningBots(
                    context = context,
                    trigger = "AutoRestartReceiver",
                    allowImmediateStart = false,
                )
                showRecoveryNotification(context)
            }
        }
    }

    companion object {
        private const val WATCHDOG_REQUEST_CODE = 1001
        private const val RECOVERY_NOTIFICATION_ID = 1002
        private const val RECOVERY_CHANNEL_ID = "bot_recovery_channel"
        const val ACTION_RESTART_BOTS = "com.heyheyon.armbandbot.ACTION_RESTART_BOTS"

        data class WatchdogScheduleResult(
            val scheduled: Boolean,
            val mode: String,
            val detail: String? = null,
        )

        fun scheduleWatchdog(context: Context): WatchdogScheduleResult {
            return try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pendingIntent = buildPendingIntent(context)
                val triggerAt = SystemClock.elapsedRealtime() + PERSISTENCE_WATCHDOG_INTERVAL_MS

                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
                Log.d("AutoRestartReceiver", "watchdog 15분 단발 예약 완료")
                WatchdogScheduleResult(true, "inexact_allow_while_idle")
            } catch (e: Exception) {
                Log.e("AutoRestartReceiver", "watchdog 예약 실패", e)
                WatchdogScheduleResult(
                    scheduled = false,
                    mode = "failed",
                    detail = e.javaClass.simpleName + ": " + (e.message ?: ""),
                )
            }
        }

        fun cancelWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = buildPendingIntent(context)
            alarmManager.cancel(pendingIntent)
            Log.d("AutoRestartReceiver", "watchdog 예약 취소 완료")
        }

        fun showRecoveryNotification(context: Context) {
            try {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notificationManager.createNotificationChannel(
                        NotificationChannel(
                            RECOVERY_CHANNEL_ID,
                            "봇 복구 안내",
                            NotificationManager.IMPORTANCE_DEFAULT,
                        )
                    )
                }
                val openAppIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openAppPendingIntent = PendingIntent.getActivity(
                    context,
                    RECOVERY_NOTIFICATION_ID,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = NotificationCompat.Builder(context, RECOVERY_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("완장봇 복구가 필요합니다")
                    .setContentText("눌러서 실행 중이던 봇을 다시 시작하세요.")
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText("Android가 백그라운드 자동 시작을 제한했습니다. 눌러서 실행 중이던 봇을 다시 시작하세요.")
                    )
                    .setContentIntent(openAppPendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                notificationManager.notify(RECOVERY_NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                Log.e("AutoRestartReceiver", "복구 안내 알림 표시 실패", e)
            }
        }

        fun cancelRecoveryNotification(context: Context) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(RECOVERY_NOTIFICATION_ID)
        }

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, AutoRestartReceiver::class.java).apply {
                action = ACTION_RESTART_BOTS
            }
            return PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
