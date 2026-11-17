package com.qinkun.accountbook.service

import android.content.Context

/**
 * 保活开关的读写与启停。
 * 状态存 SharedPreferences —— 只有一个布尔值，不值得为它引入 DataStore。
 */
object KeepAlive {

    private const val PREFS = "accountbook_prefs"
    private const val KEY_ENABLED = "keep_alive_enabled"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    /** 用户拨动开关 */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) KeepAliveService.start(ctx.applicationContext) else KeepAliveService.stop(ctx.applicationContext)
    }

    /** App 启动时调用：如果之前开过，自动恢复（系统重启后服务不会自己回来） */
    fun restoreIfNeeded(ctx: Context) {
        if (isEnabled(ctx)) KeepAliveService.start(ctx.applicationContext)
    }
}
