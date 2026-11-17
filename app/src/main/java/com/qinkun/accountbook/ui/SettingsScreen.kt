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
import androidx.core.content.ContextCompat
import com.qinkun.accountbook.AppContextHolder
import com.qinkun.accountbook.service.KeepAlive
import com.qinkun.accountbook.service.TransactionParser

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(isListenerEnabled()) }
    var testText by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testPkg by remember { mutableStateOf("com.sankuai.meituan") }
    var keepAlive by remember { mutableStateOf(KeepAlive.isEnabled(ctx)) }

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
                Text("i记账 v1.9", fontSize = 13.sp, color = AppColor.inkSoft)
                Text("数据全部存在本机，不联网、不上传", fontSize = 12.sp, color = AppColor.inkSoft)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun isListenerEnabled(): Boolean {
    // 在 Compose 里拿 applicationContext 不方便，用静态引用兜底
    val ctx = AppContextHolder.ctx ?: return false
    return NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
}
