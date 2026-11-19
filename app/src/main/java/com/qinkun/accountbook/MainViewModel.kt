package com.qinkun.accountbook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qinkun.accountbook.data.CategorySum
import com.qinkun.accountbook.data.Period
import com.qinkun.accountbook.data.Repo
import com.qinkun.accountbook.data.Transaction
import com.qinkun.accountbook.data.TxType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PeriodState(val period: Period = Period.DAY, val offset: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repo(app)

    private val _ps = MutableStateFlow(PeriodState())
    val periodState: StateFlow<PeriodState> = _ps.asStateFlow()

    /**
     * 跨天心跳。
     *
     * `Repo.rangeFor` 内部用 `Calendar.getInstance()` 取「现在」，只在 _ps 变化时重算。
     * App 挂在后台过夜再切回来，_ps 一个字都没变，首页就会停在昨天。
     * 所以前台恢复时若发现日期变了，就让这个计数器 +1，逼下游重算一次。
     */
    private val _dayTick = MutableStateFlow(0)
    private var lastDayKey: Int = dayKey()

    val rangeTitle: StateFlow<String> = combine(_ps, _dayTick) { ps, _ ->
        Repo.rangeTitle(ps.period, ps.offset)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        Repo.rangeTitle(Period.DAY, 0),
    )

    private val rangeFlow = combine(_ps, _dayTick) { ps, _ ->
        // 统计走 rangeForStats：翻到 2027 这种还没到的年份时给空区间，
        // 而不是拿一整年的「未来」去问数据库
        Repo.rangeForStats(ps.period, ps.offset)
    }

    val transactions: StateFlow<List<Transaction>> = rangeFlow
        .flatMapLatest { (f, t) -> repo.between(f, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val expense: StateFlow<Long> = rangeFlow
        .flatMapLatest { (f, t) -> repo.sumExpense(f, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val income: StateFlow<Long> = rangeFlow
        .flatMapLatest { (f, t) -> repo.sumIncome(f, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val expenseByCategory: StateFlow<List<CategorySum>> = rangeFlow
        .flatMapLatest { (f, t) -> repo.byCategory(TxType.EXPENSE, f, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val incomeByCategory: StateFlow<List<CategorySum>> = rangeFlow
        .flatMapLatest { (f, t) -> repo.byCategory(TxType.INCOME, f, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- 补录提示（常用商户 / 高频分类）----
    private val _merchants = MutableStateFlow<List<String>>(emptyList())
    val frequentMerchants: StateFlow<List<String>> = _merchants.asStateFlow()

    private val _recentCats = MutableStateFlow<List<String>>(emptyList())
    val recentCategories: StateFlow<List<String>> = _recentCats.asStateFlow()

    /** 打开「记一笔」时调用：拉最近 30 天的高频数据，让选择更快 */
    fun refreshAddHints(type: TxType) {
        viewModelScope.launch {
            _merchants.value = runCatching { repo.frequentMerchants(12) }.getOrDefault(emptyList())
            val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            _recentCats.value = runCatching { repo.recentCategories(type, since) }.getOrDefault(emptyList())
        }
    }

    // ---- 交互 ----
    fun setPeriod(p: Period) { _ps.value = PeriodState(p, 0) }

    fun shift(delta: Int) { _ps.value = _ps.value.copy(offset = _ps.value.offset + delta) }

    /** 一键回到今天：往前翻了几页之后用得上 */
    fun backToToday() { _ps.value = PeriodState(Period.DAY, 0) }

    /**
     * 每次切回前台调一次。
     *
     * 只有日期真的变了才触发重算 —— 同一天内用户在周/月视图之间来回切，
     * 切出去接个电话回来不应该被弹回日视图。
     */
    fun onForeground() {
        val today = dayKey()
        if (today != lastDayKey) {
            lastDayKey = today
            _dayTick.value += 1
        }
    }

    /** 年 * 1000 + 一年中的第几天，够区分任意两天，又不用引入时间库 */
    private fun dayKey(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
    }

    fun addManual(amountCents: Long, type: TxType, category: String, merchant: String, note: String) {
        viewModelScope.launch {
            repo.add(
                Transaction(
                    amountCents = amountCents,
                    type = type,
                    category = category,
                    merchant = merchant.ifBlank { "手动记账" },
                    sourceApp = "手动",
                    note = note,
                )
            )
        }
    }

    fun updateTx(tx: Transaction) { viewModelScope.launch { repo.update(tx) } }

    fun delete(tx: Transaction) { viewModelScope.launch { repo.remove(tx) } }
}
