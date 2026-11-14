package io.github.tgx.android

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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import io.github.tgx.android.network.DefaultNetworkStateMonitor
import io.github.tgx.android.network.TgxMtProtoProxyServer

/**
 * Owns the local MTProto listener independently of MainActivity's lifecycle.
 * The service is started only after the user taps START and remains visible
 * through an ongoing foreground notification.
 */
class TgxProxyService : Service() {

    private var proxyServer: TgxMtProtoProxyServer? = null
    private var monitoredProxyServer: TgxMtProtoProxyServer? = null
    private var networkMonitor: DefaultNetworkStateMonitor? = null
    private var foregroundStarted = false

    // Keep the notification timer in sync with the timer shown on the main screen.
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationTicker = object : Runnable {
        override fun run() {
            val startedAt = startedAtElapsedRealtimeState.longValue
            if ((proxyServer?.isRunning() != true) || (startedAt <= 0L)) return

            val elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L) / 1000L
            notificationManager().notify(
                NOTIFICATION_ID,
                buildNotification(formatElapsedTime(elapsedSeconds)),
            )
            notificationHandler.postDelayed(this, NOTIFICATION_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopProxyAndService()
                START_NOT_STICKY
            }
            ACTION_START -> startProxy(startId, forceRestart = false)
            ACTION_RESTART -> startProxy(startId, forceRestart = true)
            else -> {
                Log.w(TAG, "Ignoring unknown service action: ${intent?.action}")
                START_NOT_STICKY
            }
        }
    }

    private fun startProxy(startId: Int, forceRestart: Boolean): Int {
        var startingServer: TgxMtProtoProxyServer? = null
        try {
            startForegroundCompat(
                buildNotification(
                    if (forceRestart) uiText("Применяем новую конфигурацию…", "Applying new configuration…")
                    else uiText("Запуск локального прокси…", "Starting local proxy…"),
                )
            )

            if (forceRestart) {
                stopProxyInternal(uiText("Применяем настройки прокси…", "Applying proxy settings…"))
            } else if (proxyServer?.isRunning() == true) {
                serverRunningState.value = true
                statusMessageState.value = uiText("Прокси активен", "TGX is running")
                if (startedAtElapsedRealtimeState.longValue == 0L) {
                    startedAtElapsedRealtimeState.longValue = SystemClock.elapsedRealtime()
                }
                startNotificationTicker()
                return START_STICKY
            }

            val secret = SecretManager.getOrCreateSecret(this)
            if (!SECRET_REGEX.matches(secret)) {
                throw IllegalStateException(
                    "SecretManager returned an invalid MTProto secret"
                )
            }

            val server = TgxMtProtoProxyServer(
                secretHex = secret,
                manifestStorageDirectory = noBackupFilesDir
            )
            startingServer = server
            startNetworkMonitor(server)
            val started = server.start()
            if (!started || !server.isRunning()) {
                stopNetworkMonitor()
                server.stop()
                proxyServer = null
                serverRunningState.value = false
                startedAtElapsedRealtimeState.longValue = 0L
                statusMessageState.value = uiText("Не удалось запустить прокси — проверь Logcat", "Could not start proxy — check Logcat")
                stopForegroundCompat()
                stopSelfResult(startId)
                return START_NOT_STICKY
            }

            proxyServer = server
            serverRunningState.value = true
            startedAtElapsedRealtimeState.longValue = SystemClock.elapsedRealtime()
            statusMessageState.value = uiText("Прокси активен", "TGX is running")
            startNotificationTicker()
            Log.i(TAG, if (forceRestart) "Proxy restarted with updated settings" else "Foreground proxy service started")
            return START_STICKY
        } catch (error: Throwable) {
            Log.e(TAG, "Could not start foreground proxy service", error)
            stopNetworkMonitor()
            try {
                startingServer?.stop()
                proxyServer?.stop()
            } catch (stopError: Throwable) {
                Log.w(TAG, "Error while cleaning up failed proxy start", stopError)
            }
            proxyServer = null
            serverRunningState.value = false
            startedAtElapsedRealtimeState.longValue = 0L
            statusMessageState.value = uiText("Не удалось запустить прокси — проверь Logcat", "Could not start proxy — check Logcat")
            stopForegroundCompat()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProxyInternal(uiText("Прокси остановлен", "Proxy stopped"))
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun stopProxyAndService() {
        stopProxyInternal(uiText("Прокси остановлен", "Proxy stopped"))
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopProxyInternal(message: String) {
        notificationHandler.removeCallbacks(notificationTicker)
        stopNetworkMonitor()
        try {
            proxyServer?.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "Error while stopping proxy server", error)
        }
        proxyServer = null
        serverRunningState.value = false
        startedAtElapsedRealtimeState.longValue = 0L
        statusMessageState.value = message
        Log.i(TAG, message)
    }

    private fun startNetworkMonitor(server: TgxMtProtoProxyServer) {
        stopNetworkMonitor()
        server.setNetworkAvailable(false)
        lateinit var monitor: DefaultNetworkStateMonitor
        monitor = DefaultNetworkStateMonitor(
            source = AndroidDefaultNetworkCallbackSource(applicationContext),
            onAvailabilityChanged = { available ->
                if (networkMonitor === monitor && monitoredProxyServer === server) {
                    server.setNetworkAvailable(available)
                }
            }
        )
        monitoredProxyServer = server
        networkMonitor = monitor
        try {
            monitor.start()
        } catch (error: Throwable) {
            networkMonitor = null
            monitoredProxyServer = null
            monitor.stop()
            throw error
        }
    }

    private fun stopNetworkMonitor() {
        val monitor = networkMonitor
        networkMonitor = null
        monitoredProxyServer = null
        monitor?.stop()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun stopForegroundCompat() {
        if (!foregroundStarted) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            uiText("Прокси TGX", "TGX proxy"),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = uiText("Показывает, когда локальный прокси TGX работает", "Shows when the local TGX proxy is running")
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val builder = Notification.Builder(this, CHANNEL_ID)

        builder
            // Use a dedicated monochrome TGX wordmark for the small icon/status bar.
            .setSmallIcon(R.drawable.tgx_notification_icon)
            .setContentTitle(uiText("TGX работает", "TGX is running"))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            val contentIntent = PendingIntent.getActivity(
                this,
                CONTENT_REQUEST_CODE,
                launchIntent,
                flags
            )
            builder.setContentIntent(contentIntent)
        }

        val stopIntent = Intent(this, TgxProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            flags
        )
        builder.addAction(
            Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                uiText("Остановить", "Stop"),
                stopPendingIntent
            ).build()
        )

        return builder.build()
    }

    private fun startNotificationTicker() {
        notificationHandler.removeCallbacks(notificationTicker)
        notificationTicker.run()
    }

    private fun formatElapsedTime(totalSeconds: Long): String {
        val safeSeconds = totalSeconds.coerceAtLeast(0L)
        val hours = safeSeconds / 3600L
        val minutes = (safeSeconds % 3600L) / 60L
        val seconds = safeSeconds % 60L
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun uiText(russian: String, english: String): String =
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_UI_LANGUAGE, "ru") == "en") english else russian

    private fun notificationManager(): NotificationManager =
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private fun uiText(context: Context, russian: String, english: String): String =
            if (context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_UI_LANGUAGE, "ru") == "en") english else russian

        private const val TAG = "TGX-FOREGROUND"
        private const val CHANNEL_ID = "tgx_proxy_running"
        private const val NOTIFICATION_ID = 1443
        private const val NOTIFICATION_TICK_MS = 1_000L
        private const val CONTENT_REQUEST_CODE = 1444
        private const val STOP_REQUEST_CODE = 1445

        private const val ACTION_START = "io.github.tgx.android.action.START_PROXY"
        private const val ACTION_STOP = "io.github.tgx.android.action.STOP_PROXY"
        private const val ACTION_RESTART = "io.github.tgx.android.action.RESTART_PROXY"

        const val PREFS_NAME = "tgx_settings"
        const val KEY_UI_LANGUAGE = "ui_language"
        const val KEY_THEME_MODE = "theme_mode"
        private val SECRET_REGEX = Regex("^[0-9a-fA-F]{32}$")

        // Compose observes these states; the Service is their single writer.
        val serverRunningState = mutableStateOf(value = false)
        val statusMessageState = mutableStateOf("Ready to start")
        val startedAtElapsedRealtimeState = mutableLongStateOf(0L)

        fun requestStart(context: Context) {
            if (serverRunningState.value) return
            statusMessageState.value = uiText(context, "Запускаем TGX…", "Starting TGX…")
            val intent = Intent(context, TgxProxyService::class.java).setAction(ACTION_START)
            try {
                context.startForegroundService(intent)
            } catch (error: Throwable) {
                Log.e(TAG, "Could not request proxy start", error)
                serverRunningState.value = false
                startedAtElapsedRealtimeState.longValue = 0L
                statusMessageState.value = uiText(context, "Не удалось запустить прокси — проверь Logcat", "Could not start proxy — check Logcat")
            }
        }

        fun requestRestart(context: Context) {
            // Set the state before enqueueing the service command so the UI waits
            // for the new listener instance before opening Telegram.
            serverRunningState.value = false
            startedAtElapsedRealtimeState.longValue = 0L
            statusMessageState.value = uiText(context, "Применяем настройки прокси…", "Applying proxy settings…")
            val intent = Intent(context, TgxProxyService::class.java).setAction(ACTION_RESTART)
            try {
                context.startForegroundService(intent)
            } catch (error: Throwable) {
                Log.e(TAG, "Could not request proxy restart", error)
                serverRunningState.value = false
                startedAtElapsedRealtimeState.longValue = 0L
                statusMessageState.value = uiText(context, "Не удалось перезапустить TGX", "Could not restart TGX")
            }
        }

        fun requestStop(context: Context) {
            statusMessageState.value = uiText(context, "Останавливаем TGX…", "Stopping TGX…")
            val intent = Intent(context, TgxProxyService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (error: Throwable) {
                Log.e(TAG, "Could not request proxy stop", error)
                statusMessageState.value = uiText(context, "Не удалось остановить TGX", "Could not stop TGX")
            }
        }
    }
}
