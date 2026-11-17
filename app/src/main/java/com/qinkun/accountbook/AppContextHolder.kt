package com.qinkun.accountbook

import android.app.Application
import android.content.Context

/** 给非 Composable 的地方（如 SettingsScreen 的权限检查）提供全局 Context */
object AppContextHolder {
    var ctx: Context? = null
        private set

    fun init(app: Application) { ctx = app.applicationContext }
}
