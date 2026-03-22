package com.example.armbandbot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutoRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("AutoRestartReceiver", "onReceive 호출됨 / action=${intent?.action}")

        if (intent?.action != "com.example.armbandbot.ACTION_RESTART_BOTS") {
            Log.d("AutoRestartReceiver", "액션 불일치로 종료")
            return
        }

        Log.d("AutoRestartReceiver", "restoreRunningBots 호출 시작")
        restoreRunningBots(context)
        Log.d("AutoRestartReceiver", "restoreRunningBots 호출 완료")
    }
}