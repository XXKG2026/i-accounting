package com.qinkun.accountbook.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.qinkun.accountbook.AppContextHolder
import com.qinkun.accountbook.data.CaptureLog
import com.qinkun.accountbook.data.Repo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.qinkun.accountbook.service.KeepAlive
import com.qinkun.accountbook.service.PaymentAccessibilityService
import com.qinkun.accountbook.service.PaymentWatcher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.qinkun.accountbook.service.TransactionParser

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(isListenerEnabled()) }
    var testText by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testPkg by remember { mutableStateOf("com.sankuai.meituan") }
    var keepAlive by remember { mutableStateOf(KeepAlive.isEnabled(ctx)) }
    var promptOn by remember { mutableStateOf(PaymentWatcher.isEnabled(ctx)) }
    var scanA11y by remember { mutableStateOf(PaymentAccessibilityService.isEnabled(ctx)) }
    var usageOk by remember { mutableStateOf(PaymentWatcher.hasUsageAccess(ctx)) }
    // 从系统设置页返回时刷新权限状态
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        usageOk = PaymentWatcher.hasUsageAccess(ctx)
        promptOn = PaymentWatcher.isEnabled(ctx)
        keepAlive = KeepAlive.isEnabled(ctx)
        scanA11y = PaymentAccessibilityService.isEnabled(ctx)
    }
    var showLogs by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<CaptureLog>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // Android 13+ 显示常驻通知需要用户授权
    val notiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授权结果不影响开关本身，用户可以在系统设置里再改 */ }

    // 从系统设置返回时刷新状态
    LaunchedEffect(Unit) { granted = isListenerEnabled() }

    Column(modifier.fillMaxSize().background(AppColor.paper).verticalScroll(rememberScrollState())) {

        Text("设置", Modifier.padding(16.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColor.ink)

        // ---- 权限卡片 ----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppColor.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("通知读取权限", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColor.ink)
                    Text(
                        if (granted) "已开启" else "未开启",
                        fontSize = 13.sp,
                        color = if (granted) AppColor.income else AppColor.expense,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (granted) "能自动读取各 App 的支付通知并记账。"
                    else "未开启时无法自动记账，只能手动记。",
                    fontSize = 12.sp, color = AppColor.inkSoft
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                        granted = isListenerEnabled()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColor.ink)
                ) {
                    Text(if (granted) "去管理" else "去开启", fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { granted = isListenerEnabled() }) {
                    Text("已开启？点这里刷新状态", fontSize = 12.sp, color = AppColor.accent)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 后台保活 ----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppColor.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("后台保活", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColor.ink)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (keepAlive) "已开启：状态栏会常驻一条「i记账正在自动记录」。"
                            else "关着的时候，系统清理后台可能让自动记账悄悄失效。",
                            fontSize = 11.sp, color = AppColor.inkSoft
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = keepAlive,
                        onCheckedChange = { on ->
                            if (on && Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    ctx, Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notiPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            KeepAlive.setEnabled(ctx, on)
                            keepAlive = on
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 扫码自动抓取（无障碍）----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppColor.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("扫码自动抓取", fontSize = 15.sp,
                            fontWeight = FontWeight.Medium, color = AppColor.ink)
                        Text(
                            if (scanA11y) "已开启：微信/支付宝付款后自动记上"
                            else "扫码付款不发通知，开启后可自动识别付款结果页",
                            fontSize = 11.sp,
                            color = if (scanA11y) AppColor.income else AppColor.inkSoft
                        )
                    }
                    if (scanA11y) {
                        Text("已开启", fontSize = 12.sp, color = AppColor.income)
                    } else {
                        Button(
                            onClick = {
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColor.accent)
                        ) { Text("去开启", fontSize = 13.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "需要无障碍权限。本 App 只订阅微信和支付宝，只看付款成功页，不模拟点击、不联网。",
                    fontSize = 10.sp, color = AppColor.inkSoft
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 付款后提醒补录 ----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppColor.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("付款后提醒补录", fontSize = 15.sp,
                            fontWeight = FontWeight.Medium, color = AppColor.ink)
                        Text("扫码收款、网页支付不发通知；付完在首页提示补一笔（不弹通知）",
                            fontSize = 11.sp, color = AppColor.inkSoft)
                    }
                    Switch(
                        checked = promptOn,
                        onCheckedChange = { v ->
                            promptOn = v
                            PaymentWatcher.setEnabled(ctx, v)
                            if (v) {
                                // 提醒依赖常驻服务，顺手把保活也打开
                                if (!KeepAlive.isEnabled(ctx)) {
                                    keepAlive = true
                                    KeepAlive.setEnabled(ctx, true)
                                }
                                if (!PaymentWatcher.hasUsageAccess(ctx)) {
                                    PaymentWatcher.openUsageAccessSettings(ctx)
                                }
                            }
                        }
                    )
                }

                if (promptOn && !usageOk) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("还差一步：授权「使用情况访问」", fontSize = 12.sp, color = AppColor.expense)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { PaymentWatcher.openUsageAccessSettings(ctx) }) {
                            Text("去授权", fontSize = 12.sp, color = AppColor.accent)
                        }
                    }
                    Text("只读「哪个 App 在前台」，读不到屏幕内容",
                        fontSize = 10.sp, color = AppColor.inkSoft)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 抓取记录 ----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppColor.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("抓取记录", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColor.ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "最近 7 天收到的通知和识别结果。漏记时先看这里：是没收到，还是收到了没认出。",
                    fontSize = 11.sp, color = AppColor.inkSoft
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        scope.launch {
                            logs = runCatching {
                                Repo(ctx).captureLogs(100).first()
                            }.getOrDefault(emptyList())
                            showLogs = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColor.accent)
                ) { Text("查看记录", fontSize = 13.sp) }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 支持的 App ----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppColor.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("可自动记账的 App", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColor.ink)
                Spacer(Modifier.height(10.dp))
                TransactionParser.APP_NAMES.values.forEach { name ->
                    Text("· $name", fontSize = 13.sp, color = AppColor.inkSoft, modifier = Modifier.padding(vertical = 2.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "跳转银行 App 付款、App 被杀后台时可能漏记，漏了用手动记账补。",
                    fontSize = 11.sp, color = AppColor.inkSoft
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 规则测试 ----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppColor.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("解析测试", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColor.ink)
                Spacer(Modifier.height(4.dp))
                Text("粘贴一条通知原文，看看能不能正确认出金额", fontSize = 11.sp, color = AppColor.inkSoft)
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("com.sankuai.meituan" to "美团", "com.eg.android.AlipayGphone" to "支付宝", "com.jingdong.app.mall" to "京东").forEach { (p, n) ->
                        FilterChip(testPkg == p, { testPkg = p }, { Text(n, fontSize = 11.sp) })
                    }
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    label = { Text("通知文本") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val r = TransactionParser.parse(testPkg, testText, emptyList())
                        testResult = if (r == null) "✗ 认不出来（不会记账）"
                        else "✓ ${r.sourceApp}  ${if (r.type.name == "EXPENSE") "支出" else "收入"}  ${String.format("%.2f", r.amountCents / 100.0)} 元\n商户：${r.merchant}\n分类：${TransactionParser.guessCategory(r.sourceApp, r.merchant, r.rawText, r.type)}"
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColor.accent)
                ) { Text("测试", fontSize = 13.sp) }

                testResult?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 13.sp, color = if (it.startsWith("✓")) AppColor.income else AppColor.expense)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 关于 ----
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AppColor.surface)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("关于", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = AppColor.ink)
                Spacer(Modifier.height(8.dp))
                // 版本号一律从 PackageManager 读 —— 硬编码会跟实际安装包对不上（历史遗留 v1.9）
                val appVersion = remember {
                    val c = AppContextHolder.ctx
                    runCatching { c?.packageManager?.getPackageInfo(c.packageName, 0)?.versionName }
                        .getOrNull() ?: "?"
                }
                Text("i记账 v${appVersion}", fontSize = 13.sp, color = AppColor.inkSoft)
                Text("数据全部存在本机，不联网、不上传", fontSize = 12.sp, color = AppColor.inkSoft)
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showLogs) {
        CaptureLogDialog(
            logs = logs,
            onDismiss = { showLogs = false },
            onClear = {
                scope.launch {
                    runCatching { Repo(ctx).clearCaptureLogs() }
                    logs = emptyList()
                }
            }
        )
    }
}

private fun isListenerEnabled(): Boolean {
    // 在 Compose 里拿 applicationContext 不方便，用静态引用兜底
    val ctx = AppContextHolder.ctx ?: return false
    return NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
}

/**
 * 抓取记录弹窗。
 * 每条显示：时间 / 来源 / 结果 / 原文（脱敏后）。
 * 成功是绿色，失败是灰色 —— 一眼能扫出"哪些没记上、为什么"。
 */
@Composable
private fun CaptureLogDialog(
    logs: List<CaptureLog>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.82f),
            shape = RoundedCornerShape(14.dp),
            color = AppColor.paper
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("抓取记录", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AppColor.ink)
                    Text("${logs.count { it.success }} 成功 / ${logs.count { !it.success }} 未记", fontSize = 11.sp, color = AppColor.inkSoft)
                }
                Spacer(Modifier.height(10.dp))

                if (logs.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("还没有记录\n收到通知后会出现在这里", fontSize = 13.sp,
                            color = AppColor.inkSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(logs, key = { it.id }) { log ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppColor.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            "${fmt.format(Date(log.at))}  ${log.sourceApp}",
                                            fontSize = 11.sp, color = AppColor.inkSoft
                                        )
                                        Text(
                                            if (log.success) "已记账" else "未记账",
                                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                            color = if (log.success) AppColor.income else AppColor.inkSoft
                                        )
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        log.reason,
                                        fontSize = 12.sp,
                                        color = if (log.success) AppColor.ink else AppColor.expense.copy(alpha = 0.85f)
                                    )
                                    if (log.title.isNotBlank() || log.text.isNotBlank()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            (log.title + " " + log.text).trim().take(120),
                                            fontSize = 10.sp, color = AppColor.inkSoft, maxLines = 2
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClear) {
                        Text("清空记录", fontSize = 12.sp, color = AppColor.inkSoft)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text("关闭", fontSize = 13.sp) }
                }
            }
        }
    }
}
