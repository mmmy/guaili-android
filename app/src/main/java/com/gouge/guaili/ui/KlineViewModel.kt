package com.gouge.guaili.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.KlineRepository
import com.gouge.guaili.data.KlineResult
import com.gouge.guaili.domain.KlineChartRow
import com.gouge.guaili.domain.calculateKlineChartRows
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class KlineUiState(
    val symbol: String = "",
    val interval: String = "",
    val closedOnly: Boolean = false,
    val rows: List<KlineChartRow> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

class KlineViewModel(
    private val repository: KlineRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(KlineUiState())
    val state: StateFlow<KlineUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun load(
        symbol: String,
        interval: String,
        closedOnly: Boolean,
        force: Boolean = false,
    ) {
        if (!force && symbol == _state.value.symbol && interval == _state.value.interval &&
            closedOnly == _state.value.closedOnly &&
            _state.value.rows.isNotEmpty()
        ) {
            return
        }

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val keepsData = symbol == _state.value.symbol && interval == _state.value.interval &&
                closedOnly == _state.value.closedOnly &&
                _state.value.rows.isNotEmpty()
            _state.value = _state.value.copy(
                symbol = symbol,
                interval = interval,
                closedOnly = closedOnly,
                rows = if (keepsData) _state.value.rows else emptyList(),
                isLoading = !keepsData,
                isRefreshing = keepsData,
                errorMessage = null,
            )

            when (val result = repository.fetch(symbol, interval, closedOnly = closedOnly)) {
                is KlineResult.Success -> {
                    _state.value = _state.value.copy(
                        rows = calculateKlineChartRows(result.value),
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = null,
                    )
                }
                is KlineResult.Failure -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    companion object {
        fun factory(baseUrl: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(KlineViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return KlineViewModel(KlineRepository.create(baseUrl)) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
