package com.jianji.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jianji.app.data.entity.CategoryEntity
import com.jianji.app.data.model.RecordType
import com.jianji.app.data.repository.LedgerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CategoryUiState(
    val tab: RecordType = RecordType.EXPENSE,
    val categories: List<CategoryEntity> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModel(private val repo: LedgerRepository) : ViewModel() {

    private val tab = MutableStateFlow(RecordType.EXPENSE)

    val ui: StateFlow<CategoryUiState> = combine(
        tab.flatMapLatest { repo.categoriesFlow(it) },
        repo.categoryCountsFlow(),
        tab,
    ) { cats, counts, t ->
        CategoryUiState(tab = t, categories = cats, counts = counts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryUiState())

    fun switchTab(t: RecordType) {
        tab.value = t
    }

    fun add(name: String, emoji: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.addCategory(name, emoji, tab.value) }
    }

    fun rename(id: String, name: String, emoji: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.renameCategory(id, name, emoji) }
    }

    /** 删除分类：软删除 + 历史记录归入「其他」 */
    fun delete(id: String) {
        viewModelScope.launch { repo.deleteCategory(id) }
    }
}
