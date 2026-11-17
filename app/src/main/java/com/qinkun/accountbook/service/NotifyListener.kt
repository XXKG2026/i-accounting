package com.qinkun.accountbook.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.qinkun.accountbook.data.Repo
import com.qinkun.accountbook.data.Transaction
import com.qinkun.accountbook.util.PiiMasker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotifyListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: Repo

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (!TransactionParser.APP_NAMES.containsKey(pkg)) return

        val n: Notification = sbn.notification ?: return
        val e = n.extras ?: return

        fun str(key: String): String = e.getCharSequence(key)?.toString()?.trim() ?: ""
        val title = str(Notification.EXTRA_TITLE)
        val texts = listOf(
            str(Notification.EXTRA_TEXT),
            str(Notification.EXTRA_BIG_TEXT),
            str(Notification.EXTRA_SUB_TEXT),
            str(Notification.EXTRA_INFO_TEXT),
        ).filter { it.isNotBlank() }

        if (texts.isEmpty() && title.isBlank()) return

        val parsed = TransactionParser.parse(pkg, title, texts) ?: return
        val at = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()

        scope.launch {
            try {
                val id = repo.addIfNew(
                    Transaction(
                        amountCents = parsed.amountCents,
                        type = parsed.type,
                        category = TransactionParser.guessCategory(parsed.sourceApp, parsed.merchant, parsed.rawText, parsed.type),
                        merchant = PiiMasker.mask(parsed.merchant),
                        sourceApp = parsed.sourceApp,
                        note = "",
                        occurredAt = at,
                        rawText = PiiMasker.mask(parsed.rawText),
                    )
                )
                if (id < 0) {
                    Log.d(TAG, "重复通知，跳过: ${parsed.rawText.take(40)}")
                } else {
                    Log.i(TAG, "已记账: ${parsed.sourceApp} ${parsed.type} ${parsed.amountCents / 100.0} ${parsed.merchant}")
                }
            } catch (ex: Exception) {
                Log.e(TAG, "入库失败", ex)
            }
        }
    }

    override fun onListenerConnected() {
        Log.i(TAG, "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "通知监听已断开")
    }

    companion object { const val TAG = "NotifyListener" }
}
