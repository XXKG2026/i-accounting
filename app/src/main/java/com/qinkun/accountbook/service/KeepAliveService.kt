package com.qinkun.accountbook.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.qinkun.accountbook.R
import com.qinkun.accountbook.MainActivity
import com.qinkun.accountbook.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 前台保活服务。
 *
 * 解决什么问题：三星 One UI 等国产/定制系统会激进清理后台进程。
 * 一旦 App 进程被清掉，NotificationListenerService 会被系统解绑，
 * 而且**不会自动重新绑定** —— 于是在你毫不知情的情况下，自动记账彻底失效。
 *
 * 办法：挂一个常驻通知，让系统认为这个 App 是"正在前台工作"的，
 * 从而大幅降低被杀概率。本服务自身不做任何业务逻辑，纯占位。
 *
 * 默认关闭，由用户在设置里主动开启 —— 不想看到常驻通知的人可以不开。
 */
class KeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ 必须声明前台服务类型
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
            stopSelf()
            return START_NOT_STICKY
        }
        startWatching()
        // 进程被杀后让系统尝试重启
        return START_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 付款后提醒的巡逻循环。
     *
     * 只在「开关开着 + 有使用情况权限 + 屏幕亮着」时才真干活；
     * 屏幕灭掉就退到 5 分钟一次 —— 用户不在看手机，没必要勤查。
     */
    private fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            val pm = getSystemService(PowerManager::class.java)
            while (true) {
                val screenOn = pm?.isInteractive ?: true
                if (screenOn && PaymentWatcher.isEnabled(this@KeepAliveService) &&
                    PaymentWatcher.hasUsageAccess(this@KeepAliveService)
                ) {
                    runCatching { checkPaymentOnce() }
                        .onFailure { Log.e(TAG, "付款提醒检查失败", it) }
                }
                delay(if (screenOn) 45_000L else 5 * 60_000L)
            }
        }
    }

    private suspend fun checkPaymentOnce() {
        val (pkg, leftAt) = PaymentWatcher.detectJustLeft(this) ?: return

        // 5 分钟内提醒过一次就不再打扰
        val now = System.currentTimeMillis()
        if (now - PaymentWatcher.lastPromptAt(this) < 5 * 60_000L) return

        // 离开支付 App 之后已经有新账 → 说明自动记账抓到了，别多嘴
        val lastTx = runCatching { Repo(this).lastTxAt() }.getOrNull()
        if (lastTx != null && lastTx >= leftAt) return

        PaymentWatcher.markPrompted(this, now)
        showPromptNotification(PaymentWatcher.appLabel(pkg))
    }

    private fun showPromptNotification(appLabel: String) {
        ensurePromptChannel(this)
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(MainActivity.EXTRA_OPEN_ADD, true)
        }
        val pi = PendingIntent.getActivity(
            this, 2001, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, PROMPT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("刚才是不是付了钱？")
            .setContentText("$appLabel 里好像有一笔，自动记账没抓到。点这里补一笔")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$appLabel 里好像有一笔，自动记账没抓到（这类付款不发通知）。点这里 1 秒补上。")
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(PROMPT_NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(ctx: Context): android.app.Notification {
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("i记账正在自动记录")
            .setContentText("保持后台运行，付款通知才抓得到")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PROMPT_CHANNEL_ID = "payment_prompt_channel"
        private const val PROMPT_NOTIFICATION_ID = 2001

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                "自动记账运行状态",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "让系统不要清掉记账进程"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }

        fun ensurePromptChannel(ctx: Context) {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(PROMPT_CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                PROMPT_CHANNEL_ID,
                "付款后提醒补录",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "检测到你可能付款后，提醒补一笔（这类付款不发系统通知）"
                setShowBadge(true)
            }
            mgr.createNotificationChannel(ch)
        }

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "启动保活失败", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "停止保活失败", e)
            }
        }
    }
}
