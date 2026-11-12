package com.jianji.app.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.model.RecordType
import com.jianji.app.data.prefs.SettingsStore
import com.jianji.app.data.repository.LedgerRepository
import com.jianji.app.util.Dates
import com.jianji.app.util.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AddUiState(
    val editing: Boolean = false,
    val type: RecordType = RecordType.EXPENSE,
    /** 键盘输入承载串："32." / "32.5" / "32.55" */
    val amountInput: String = "",
    val categoryId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val categories: List<CategoryEntity> = emptyList(),
    val recentCategoryIds: List<String> = emptyList(),
    /** 金额/分类缺失提示 */
    val error: Boolean = false,
)

/** 新增与编辑共用一页；recordId 非空即编辑模式 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddRecordViewModel(
    private val repo: LedgerRepository,
    private val settingsStore: SettingsStore,
    private val recordId: String?,
) : ViewModel() {

    private val state = MutableStateFlow(AddUiState())
    val finished = MutableStateFlow(false)

    /** 仅「新建并保存成功」时写入日期：明细页据此切月并定位到该日分组 */
    val savedEpochDay = MutableStateFlow<Int?>(null)

    val ui: StateFlow<AddUiState> = combine(
        state,
        state.flatMapLatest { repo.categoriesFlow(it.type) },
        settingsStore.settings,
    ) { s, cats, settings ->
        val leaves = cats.filter { it.parentId != null }
        val recentIds = settings.recentCategoryIds(s.type)
        val selectedId = when {
            s.editing && s.categoryId != null -> s.categoryId
            s.categoryId != null && leaves.any { it.id == s.categoryId } -> s.categoryId
            else -> recentIds.firstOrNull { id -> leaves.any { it.id == id } }
                ?: leaves.firstOrNull()?.id
        }
        s.copy(categories = cats, recentCategoryIds = recentIds, categoryId = selectedId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddUiState())

    init {
        if (recordId != null) {
            viewModelScope.launch {
                val r = repo.recordOnce(recordId) ?: return@launch
                state.value = AddUiState(
                    editing = true,
                    type = r.type,
                    amountInput = Money.minorToInput(r.amountMinor),
                    categoryId = r.categoryId,
                    date = Dates.ofEpochDay(r.dateEpochDay),
                    note = r.note,
                )
            }
        }
    }

    fun setType(t: RecordType) {
        state.value = state.value.copy(type = t, categoryId = null, error = false)
    }

    fun onKey(ch: Char) {
        state.value = state.value.copy(
            amountInput = Money.append(state.value.amountInput, ch),
            error = false,
        )
    }

    fun backspace() {
        state.value = state.value.copy(amountInput = Money.backspace(state.value.amountInput))
    }

    fun clear() {
        state.value = state.value.copy(amountInput = "", error = false)
    }

    fun selectCategory(id: String) {
        state.value = state.value.copy(categoryId = id, error = false)
    }

    fun setDate(d: LocalDate) {
        state.value = state.value.copy(date = d)
    }

    fun setNote(n: String) {
        state.value = state.value.copy(note = n.take(50))
    }

    fun save() {
        val s = ui.value
        // 算式求值：纯数字或四则表达式均可，非法返回 null
        val amount = Money.evalExprMinor(s.amountInput)
        val cat = s.categoryId
        if (amount == null || cat == null) {
            state.value = s.copy(error = true)
            return
        }
        viewModelScope.launch {
            if (s.editing) {
                repo.updateRecord(recordId!!, s.type, amount, cat, s.date, s.note)
            } else {
                repo.addRecord(s.type, amount, cat, s.date, s.note)
                // 只有新建成功才回传落点；编辑、删除、取消都不写
                savedEpochDay.value = Dates.epochDay(s.date)
            }
            settingsStore.recordCategoryUse(s.type, cat)
            finished.value = true
        }
    }

    fun delete() {
        if (recordId == null) return
        viewModelScope.launch {
            repo.softDeleteRecord(recordId)
            finished.value = true
        }
    }
}
