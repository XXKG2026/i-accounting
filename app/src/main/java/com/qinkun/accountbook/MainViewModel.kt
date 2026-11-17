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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PeriodState(val period: Period = Period.MONTH, val offset: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repo(app)

    private val _ps = MutableStateFlow(PeriodState())
    val periodState: StateFlow<PeriodState> = _ps.asStateFlow()

    val rangeTitle: StateFlow<String> = _ps
        .map { Repo.rangeTitle(it.period, it.offset) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "本月")

    private val rangeFlow = _ps.map { Repo.rangeFor(it.period, it.offset) }

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
