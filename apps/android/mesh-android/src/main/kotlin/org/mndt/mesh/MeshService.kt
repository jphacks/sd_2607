package org.mndt.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

object MeshPermissions {
    fun required(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT <= 32) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()
    fun granted(context: Context) = required().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

class MeshService : Service() {
    private val coordinator by lazy { MeshCoordinator.get(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            coordinator.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!MeshPermissions.granted(this)) {
            coordinator.reportError("Nearby に必要な権限がありません")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.mesh_channel), NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mesh)
                .setContentTitle(getString(R.string.mesh_notification_title))
                .setContentText(getString(R.string.mesh_notification_body)).setOngoing(true)
                .addAction(Notification.Action.Builder(null, getString(R.string.mesh_stop), stop).build())
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                notification.setContentIntent(PendingIntent.getActivity(this, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            ServiceCompat.startForeground(this, 71, notification.build(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            coordinator.start()
        } catch (_: Exception) {
            coordinator.reportError("前景サービスを開始できません。画面から権限を確認して再試行してください")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { coordinator.stop(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "mndt-mesh"
        private const val ACTION_STOP = "org.mndt.mesh.STOP"
        /** Start only from a visible Activity after Nearby permissions are granted. */
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, MeshService::class.java)); MeshCoordinator.get(context).stop() }
    }
}
