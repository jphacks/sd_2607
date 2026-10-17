package org.mndt.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Relay IO has its own foreground lifetime and never requires Nearby permissions. */
class OnlineService : Service() {
    private val coordinator by lazy { MeshCoordinator.get(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            coordinator.setOnlineEnabled(false)
            finish()
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.online_channel), NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 3, Intent(this, OnlineService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mesh)
                .setContentTitle(getString(R.string.online_notification_title))
                .setContentText(getString(R.string.online_notification_body)).setOngoing(true)
                .addAction(Notification.Action.Builder(null, getString(R.string.mesh_stop), stop).build())
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                notification.setContentIntent(PendingIntent.getActivity(this, 4, it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            ServiceCompat.startForeground(this, 72, notification.build(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            coordinator.setOnlineServiceActive(true)
        } catch (_: Exception) {
            coordinator.reportError("オンライン前景サービスを開始できません。画面から再試行してください")
            finish()
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator.reportError("Android のオンライン通信時間上限に達しました。搬出待ちは保持。画面を開くと再開します")
        finish()
    }
    private fun finish() {
        coordinator.setOnlineServiceActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { coordinator.setOnlineServiceActive(false); super.onDestroy() }
    companion object {
        private const val CHANNEL = "mndt-online"
        private const val ACTION_STOP = "org.mndt.mesh.STOP_ONLINE"
        /** Call only from a visible Activity, after the user has opted in to a relay. */
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, OnlineService::class.java))
    }
}
