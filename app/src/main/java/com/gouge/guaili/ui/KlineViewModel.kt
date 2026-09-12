package com.gouge.guaili.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.KlineRepository
import com.gouge.guaili.data.KlineResult
import com.gouge.guaili.data.AlertDto
import com.gouge.guaili.data.AlertEventDto
import com.gouge.guaili.data.AlertPatchDto
import com.gouge.guaili.data.AlertRepository
import com.gouge.guaili.data.AlertRequestDto
import com.gouge.guaili.data.AlertResult
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
    val alerts: List<AlertDto> = emptyList(),
    val alertEvents: Map<Long, List<AlertEventDto>> = emptyMap(),
    val alertError: String? = null,
    val isAlertBusy: Boolean = false,
)

class KlineViewModel(
    private val repository: KlineRepository,
    private val alertRepository: AlertRepository? = null,
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
            loadAlerts()
        }
    }

    fun loadAlerts() {
        val alertsApi = alertRepository ?: return
        viewModelScope.launch {
            when (val result = alertsApi.list()) {
                is AlertResult.Success -> _state.value = _state.value.copy(
                    alerts = result.value,
                    alertError = null,
                )
                is AlertResult.Failure -> _state.value = _state.value.copy(alertError = result.message)
            }
        }
    }

    fun createAlert(request: AlertRequestDto, onResult: (AlertDto?) -> Unit = {}) {
        val alertsApi = alertRepository ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isAlertBusy = true, alertError = null)
            when (val result = alertsApi.create(request)) {
                is AlertResult.Success -> {
                    _state.value = _state.value.copy(
                        alerts = (_state.value.alerts + result.value).sortedByDescending { it.id },
                        isAlertBusy = false,
                    )
                    onResult(result.value)
                }
                is AlertResult.Failure -> {
                    _state.value = _state.value.copy(isAlertBusy = false, alertError = result.message)
                    onResult(null)
                }
            }
        }
    }

    fun updateAlert(id: Long, request: AlertPatchDto, onResult: (AlertDto?) -> Unit = {}) {
        val alertsApi = alertRepository ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isAlertBusy = true, alertError = null)
            when (val result = alertsApi.update(id, request)) {
                is AlertResult.Success -> {
                    _state.value = _state.value.copy(
                        alerts = _state.value.alerts.map { if (it.id == id) result.value else it },
                        isAlertBusy = false,
                    )
                    onResult(result.value)
                }
                is AlertResult.Failure -> {
                    _state.value = _state.value.copy(isAlertBusy = false, alertError = result.message)
                    onResult(null)
                }
            }
        }
    }

    fun deleteAlert(id: Long, onResult: (Boolean) -> Unit = {}) {
        val alertsApi = alertRepository ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isAlertBusy = true, alertError = null)
            when (val result = alertsApi.delete(id)) {
                is AlertResult.Success -> {
                    _state.value = _state.value.copy(
                        alerts = _state.value.alerts.filterNot { it.id == id },
                        alertEvents = _state.value.alertEvents - id,
                        isAlertBusy = false,
                    )
                    onResult(true)
                }
                is AlertResult.Failure -> {
                    _state.value = _state.value.copy(isAlertBusy = false, alertError = result.message)
                    onResult(false)
                }
            }
        }
    }

    fun loadAlertEvents(id: Long) {
        val alertsApi = alertRepository ?: return
        viewModelScope.launch {
            when (val result = alertsApi.events(id)) {
                is AlertResult.Success -> _state.value = _state.value.copy(
                    alertEvents = _state.value.alertEvents + (id to result.value),
                    alertError = null,
                )
                is AlertResult.Failure -> _state.value = _state.value.copy(alertError = result.message)
            }
        }
    }

    companion object {
        fun factory(baseUrl: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(KlineViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return KlineViewModel(
                            KlineRepository.create(baseUrl),
                            AlertRepository.create(baseUrl),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
