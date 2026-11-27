package com.ilyemir.antena.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ilyemir.antena.MainActivity
import com.ilyemir.antena.R

/**
 * Overlay (SYSTEM_ALERT_WINDOW) izni yokken açılışta Activity başlatma denemesi.
 *
 * Android 10+ arka plandan Activity başlatmayı engeller; ROM'ların kabul ettiği
 * resmi istisnalardan biri "uygulamanın çalışan bir ön plan servisi olması"dır.
 * BOOT_COMPLETED aynı zamanda arka plandan ön plan servisi başlatmanın da
 * istisnası olduğu için zincir çalışır: BootReceiver -> bu servis -> MainActivity.
 *
 * Mi Box (Android 14) ROM'u SYSTEM_ALERT_WINDOW appops kaydını her açılışta
 * "default"a düşürdüğüünden bu yol kutudaki kalıcı tek yoldur.
 */
class BootLaunchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val launched = runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
            true
        }.getOrDefault(false)
        Log.i(TAG, "boot activity start via foreground service: $launched")
        stopSelf()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        BootLaunchNotifications.ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, BootLaunchNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Yayın açılıyor…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // specialUse tipi Android 14+ zorunlu; eski sürümlerde 0 olarak geçilir.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, BootLaunchNotifications.LAUNCH_NOTIFICATION_ID, notification, type)
    }

    private companion object {
        const val TAG = "AntenaBoot"
    }
}

/** BootReceiver ile BootLaunchService arasında paylaşılan bildirim yardımcıları. */
object BootLaunchNotifications {
    const val CHANNEL_ID = "boot"
    const val PERMISSION_NOTIFICATION_ID = 4201
    const val LAUNCH_NOTIFICATION_ID = 4202

    fun ensureChannel(context: Context): android.app.NotificationManager {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return manager
    }
}
