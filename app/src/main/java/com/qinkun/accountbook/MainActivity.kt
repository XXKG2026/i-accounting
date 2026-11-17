package com.qinkun.accountbook

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.qinkun.accountbook.ui.DetailScreen
import com.qinkun.accountbook.ui.HomeScreen
import com.qinkun.accountbook.ui.SettingsScreen
import com.qinkun.accountbook.ui.StatsScreen

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /** 是不是要自动弹出「记一笔」—— 由付款提醒通知点进来时为 true */
    private val openAdd = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.init(application)
        com.qinkun.accountbook.service.KeepAlive.restoreIfNeeded(this)
        com.qinkun.accountbook.service.PaymentWatcher.restorePending(this)
        openAdd.value = intent?.getBooleanExtra(EXTRA_OPEN_ADD, false) ?: false
        setContent {
            AppRoot(vm, openAdd)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) openAdd.value = true
    }

    companion object {
        const val EXTRA_OPEN_ADD = "extra_open_add"
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun AppRoot(vm: MainViewModel, openAdd: MutableState<Boolean> = mutableStateOf(false)) {
    val tabs = listOf(
        Tab("首页", Icons.Filled.Home),
        Tab("统计", Icons.Filled.PieChart),
        Tab("明细", Icons.AutoMirrored.Filled.List),
        Tab("设置", Icons.Filled.Settings),
    )
    var idx by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = idx == i,
                        onClick = { idx = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { pad ->
        val m = Modifier.padding(pad)
        when (idx) {
            0 -> HomeScreen(vm, m, openAdd)
            1 -> StatsScreen(vm, m)
            2 -> DetailScreen(vm, m)
            else -> SettingsScreen(m)
        }
    }
}
