package com.qinkun.accountbook.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机后把保活服务拉起来。
 *
 * 为什么需要它：通知监听本身由系统在开机时重新绑定（系统负责），
 * 但**付后提醒的巡逻循环**跑在 `KeepAliveService` 里。手机重启后如果没人
 * 打开 App，那个循环就一直不跑，提醒永远不出现，而设置页还显示"已开启"。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val ctx = context.applicationContext

        // 用户没开保活就别自作主张拉起前台服务
        if (!KeepAlive.isEnabled(ctx)) {
            Log.i(TAG, "开机事件（$action）：保活未开启，跳过")
            return
        }

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                // 开机广播在 Android 12+ 属于「允许后台启动前台服务」的豁免场景，可以直接拉
                Log.i(TAG, "开机：拉起保活服务")
                KeepAliveService.start(ctx)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 覆盖安装**不在豁免名单里**，这里调 startForegroundService 必然抛
                // ForegroundServiceStartNotAllowedException（start() 内部 catch 住不崩，
                // 但等于白调一次还刷一条错误日志）。
                // 安装完用户会打开 App，MainActivity 里的 restoreIfNeeded() 会补上。
                Log.i(TAG, "覆盖安装完成：等用户打开 App 时由 restoreIfNeeded 拉起")
            }

            else -> Unit
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
