package com.dicar.vehicle.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dicar.vehicle.MainActivity
import com.dicar.vehicle.R
import com.dicar.vehicle.appContainer
import com.dicar.vehicle.data.VehicleRepository
import com.dicar.vehicle.data.model.VehicleState
import com.dicar.vehicle.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * F41 前台服务：持有 Repository 的轮询引用，界面关闭后继续采集；
 * 通知栏常驻显示「车速 | SOC | 空调」。
 */
class VehicleMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: VehicleRepository
    private var acquired = false

    override fun onCreate() {
        super.onCreate()
        repository = appContainer.repository
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(summary(repository.state.value)),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        repository.acquire()
        acquired = true

        scope.launch {
            repository.state
                .map(::summary)
                .distinctUntilChanged()
                .conflate()
                .collect { text ->
                    notify(text)
                    delay(NOTIFY_THROTTLE_MS) // 通知刷新限流，避免每秒重建通知
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (acquired) repository.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun summary(state: VehicleState): String {
        val speed = Format.num(state.speed, unit = "km/h")
        val soc = Format.num(state.soc, 1, "%")
        val ac = when (state.acOn) {
            null -> Format.NA
            false -> "关"
            true -> "开 " + Format.num(state.acTempDriver, 1, "℃")
        }
        return "车速 $speed  |  SOC $soc  |  空调 $ac"
    }

    @SuppressLint("MissingPermission") // 已用 areNotificationsEnabled 判断
    private fun notify(text: String) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_car)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(
            0, "停止采集",
            PendingIntent.getService(
                this, 1,
                Intent(this, VehicleMonitorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "车辆数据采集", NotificationManager.IMPORTANCE_LOW).apply {
            description = "后台持续采集车辆数据时显示"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "vehicle_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_THROTTLE_MS = 2_000L
        private const val ACTION_STOP = "com.dicar.vehicle.action.STOP_MONITOR"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VehicleMonitorService::class.java))
        }
    }
}
