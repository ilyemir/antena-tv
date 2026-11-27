package com.ilyemir.antena.server

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ilyemir.antena.AntenaApplication
import com.ilyemir.antena.MainActivity
import com.ilyemir.antena.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Mi Box açılışında (BOOT_COMPLETED) uygulamayı kendiliğinden öne çıkarır.
 *
 * Android 10+ arka plandan Activity başlatmayı engeller; iki ayrı yol denenir:
 *
 *  1. `Settings.canDrawOverlays(context)` doğruysa (SYSTEM_ALERT_WINDOW / "diğer
 *     uygulamaların üzerinde göster" izni) kısıt uygulanmaz → Activity doğrudan
 *     `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` ile başlatılır.
 *  2. İzin yoksa [BootLaunchService] ön plan servisi üzerinden denenir (ön plan
 *     servisi de ROM'ların kabul ettiği bir istisnadır) ve kullanıcıya tek
 *     seferlik izin bildirimi gösterilir.
 *
 * Not: Mi Box ROM'u `appops ... SYSTEM_ALERT_WINDOW allow` kaydını her açılışta
 * sıfırladığı için kutudaki kalıcı yol 2. maddedir; 1. madde izin verildiyse
 * daha hızlıdır. Bildirim için `pm grant ... POST_NOTIFICATIONS` gerekir.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? AntenaApplication ?: return
        val enabled = runBlocking { app.container.settings.bootStart.first() }
        if (!enabled) return

        val overlayAllowed = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        if (overlayAllowed) {
            Log.i(TAG, "overlay izni var: MainActivity doğrudan başlatılıyor")
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    ),
                )
            }.onFailure { Log.w(TAG, "doğrudan başlatma başarısız", it) }
        } else {
            Log.i(TAG, "overlay izni yok: ön plan servisi + izin bildirimi")
            runCatching {
                context.startForegroundService(Intent(context, BootLaunchService::class.java))
            }.onFailure { Log.w(TAG, "ön plan servisi başlatılamadı", it) }
            runCatching { notifyMissingOverlayPermission(context) }
        }
    }

    /** Kullanıcıyı tek seferlik izin ekranına götüren bildirim. */
    private fun notifyMissingOverlayPermission(context: Context) {
        val manager = BootLaunchNotifications.ensureChannel(context)
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            BootLaunchNotifications.PERMISSION_NOTIFICATION_ID,
            NotificationCompat.Builder(context, BootLaunchNotifications.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentTitle("Açılışta otomatik başlatma için izin gerekiyor")
                .setContentText("Dokun ve \"Diğer uygulamaların üzerinde göster\" iznini aç; kutu açılınca Antena kendini açar.")
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build(),
        )
    }

    private companion object {
        const val TAG = "AntenaBoot"
    }
}
