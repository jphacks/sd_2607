package org.mndt.app.alert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.mndt.app.MainActivity
import org.mndt.app.location.LocationRepository
import org.mndt.mesh.MeshCoordinator
import org.mndt.mesh.MeshState

/**
 * Local notifications for important official hazards and nearby danger reports from others ([alertCandidates]). Watches the coordinator's
 * snapshot for the whole process, so it keeps working in the background while MeshService holds the process; every
 * source (mesh, relay, PDS) ends up in that snapshot. Re-evaluated on every snapshot: a post can turn official later
 * (trust config loaded, clock trusted), and [handled] keeps each one to a single alert across restarts.
 */
class HazardNotifier(private val context: Context, private val mesh: MeshCoordinator, private val location: LocationRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var handled = prefs.getStringSet(HANDLED, null).orEmpty().toSet()
    private var handledIds = handledIds(handled)
    private var batch: AlertBatch? = null

    fun start() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "重要な災害情報", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "自分以外が発信した、公式の重大な災害情報と近くの危険情報"
            })
        scope.launch { mesh.state.collect { if (it.ready) check(it) } }
    }

    private fun check(state: MeshState) {
        val now = System.currentTimeMillis()
        // A fix older than a day says little about where the phone is now: treated as unknown.
        val here = location.location.value?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos <= FIX_MAX_AGE_NANOS }
            ?.let { it.latitude to it.longitude }
        val fresh = alertCandidates(state.posts, state.publicKey, now / 1000, here, handledIds)
        if (fresh.isEmpty()) return
        // Handled even when switched off or not permitted: turning alerts on later must not ring for what already came in.
        handled = pruneHandled(handled + fresh.map { handledEntry(it.post) }, now / 1000)
        handledIds = handledIds(handled)
        prefs.edit { putStringSet(HANDLED, handled) }
        if (!permitted()) return
        val next = nextBatch(batch, fresh.filter { enabled(context, it.genre) }, now) ?: return
        val updating = next.notificationId == batch?.notificationId
        batch = next
        runCatching { show(next, updating) }
            .onFailure { runCatching { android.util.Log.w("HazardNotifier", "notify failed", it) } }
    }

    private fun permitted() = (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun show(batch: AlertBatch, updating: Boolean) {
        val open = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_POST, batch.head.post.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val text = batch.text()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(org.mndt.mesh.R.drawable.ic_mesh)
            .setContentTitle(batch.title()).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_ALARM).setWhen(batch.startedAt).setShowWhen(true).setAutoCancel(true)
            // A batch update only changes the count; the first alert already rang.
            .setOnlyAlertOnce(updating)
            .setContentIntent(PendingIntent.getActivity(context, batch.notificationId, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(TAG, batch.notificationId, notification)
    }

    companion object {
        private const val CHANNEL = "mosimo-hazard"
        private const val FIX_MAX_AGE_NANOS = 24 * 3_600_000_000_000L
        /** Tagged so batch IDs never collide with MeshService's ongoing notification. */
        private const val TAG = "hazard"
        private const val PREFS = "hazard-alerts"
        private const val HANDLED = "handled"
        /** The former single switch: saved off, every genre starts off. */
        private const val LEGACY_ENABLED = "hazardAlerts"
        private fun key(genre: AlertGenre) = "hazardAlerts.${genre.name.lowercase()}"
        /** Settings switch per genre, on by default; kept with the other app preferences. */
        fun enabled(context: Context, genre: AlertGenre): Boolean = context.getSharedPreferences("app", Context.MODE_PRIVATE).let {
            it.getBoolean(key(genre), it.getBoolean(LEGACY_ENABLED, true))
        }
        fun setEnabled(context: Context, genre: AlertGenre, value: Boolean) =
            context.getSharedPreferences("app", Context.MODE_PRIVATE).edit { putBoolean(key(genre), value) }
    }
}
