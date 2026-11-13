package org.mcfso.irix.gnss

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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mcfso.irix.AppContainer
import org.mcfso.irix.MainActivity
import org.mcfso.irix.R

/** User-started non-exported service; never restarted from boot or background. */
class GnssCalibrationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var everRunning = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.gnss_service_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        val controller = AppContainer.gnss
        controller.refreshCapabilities()
        val caps = controller.state.value.capabilities
        if (!caps.fineLocationGranted || !caps.gpsEnabled || !caps.gpsAvailable) {
            controller.reportError(getString(R.string.gnss_service_permission_required)); stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(controller.state.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (e: RuntimeException) {
            controller.reportError(getString(R.string.gnss_service_start_error, e.message ?: e.javaClass.simpleName))
            stopSelf(); return START_NOT_STICKY
        }
        started = true
        controller.start()
        scope.launch {
            delay(500L)
            while (true) {
                val state = controller.state.value
                if (state.running) everRunning = true
                if (!state.running && (everRunning || state.error != null)) { stopSelf(); break }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                delay(1_000L)
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(state: GnssUiState): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, GnssCalibrationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sample = state.latest
        val stale = sample == null || SystemClock.elapsedRealtimeNanos() - sample.monoNs > 2_000_000_000L
        val text = if (stale) getString(R.string.gnss_service_waiting)
            else getString(R.string.gnss_service_sample, sample!!.source, sample.svCount)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_gnss)
            .setContentTitle(getString(R.string.gnss_service_title)).setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.stop), stop).build()
    }

    override fun onDestroy() {
        scope.cancel()
        if (started) AppContainer.gnss.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "gnss_calibration"
        private const val NOTIFICATION_ID = 47810
        private const val ACTION_START = "org.mcfso.irix.gnss.START"
        private const val ACTION_STOP = "org.mcfso.irix.gnss.STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context,
                    Intent(context, GnssCalibrationService::class.java).setAction(ACTION_START))
            } catch (e: RuntimeException) {
                AppContainer.gnss.reportError(context.getString(R.string.gnss_service_start_error, e.message ?: e.javaClass.simpleName))
            }
        }
        fun stop(context: Context) { context.stopService(Intent(context, GnssCalibrationService::class.java)) }
    }
}
