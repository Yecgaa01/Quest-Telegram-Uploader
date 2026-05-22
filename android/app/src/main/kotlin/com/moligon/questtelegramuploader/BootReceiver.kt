package com.moligon.questtelegramuploader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!QuestUploadService.isAutoEnabled(context)) return
        ContextCompat.startForegroundService(context, Intent(context, QuestUploadService::class.java))
    }
}
