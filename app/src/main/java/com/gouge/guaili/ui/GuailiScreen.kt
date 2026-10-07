package com.gouge.guaili.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.settings.LayoutMode
import com.gouge.guaili.settings.MarketView
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.gouge.guaili.data.normalizeServerSignalsBaseUrl
import com.gouge.guaili.signals.ServerSignalItem
import com.gouge.guaili.data.MarketTvTickerStore
import com.gouge.xbot.ui.MainViewModel
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
internal fun GuailiScreen(
    viewModel: GuailiViewModel,
    signalsViewModel: ServerSignalsViewModel,
    xbotViewModel: MainViewModel,
    requestedKlineTarget: KlineTarget? = null,
    onRequestedKlineConsumed: () -> Unit = {},
    onOpenAppSettings: (() -> Unit)? = null,
    onOpenTvAlerts: (MarketTvAlertEntry?) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tvState by xbotViewModel.uiState.collectAsStateWithLifecycle()
    val tvTickerStore = remember(state.settings.baseUrl) { MarketTvTickerStore(context, state.settings.baseUrl) }
    var tvTickers by remember(tvTickerStore) { mutableStateOf(tvTickerStore.read()) }
    var tvNow by remember { mutableStateOf(Instant.now()) }
    val tvEntriesBySymbol = remember(tvState.isAuthenticated, tvState.alertConfigs, tvState.visibleAlertIds,
        tvState.tvAlertsByCookieId, state.symbols, tvTickers) {
        state.symbols.associateWith { symbol -> marketTvEntries(tvState, tvTickers[symbol] ?: defaultMarketTvTicker(symbol)) }
    }
    val tvSummaries = remember(tvEntriesBySymbol, tvNow) {
        summarizeMarketTvEntries(tvEntriesBySymbol, tvNow)
    }
    val alertsViewModel: MarketPriceAlertsViewModel = viewModel(
        key = "market-price-alerts:${state.settings.baseUrl.trimEnd('/')}",
        factory = MarketPriceAlertsViewModel.factory(context, state.settings.baseUrl),
    )
    val alertsState by alertsViewModel.state.collectAsStateWithLifecycle()
    val alertSummaries = remember(alertsState) { alertsState.summaries() }
    val signalsState by signalsViewModel.state.collectAsStateWithLifecycle()
    val signalListState = rememberLazyListState()
    val tableListState = rememberLazyListState()
    val groupListState = rememberLazyListState()
    val focusListState = rememberLazyListState()
    val paneListState = rememberLazyListState()
    val tableHorizontal = rememberScrollState()
    val signalContentHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var signalRestore by remember { mutableStateOf<MarketScrollAnchor?>(null) }
    var paneRestore by remember { mutableStateOf<MarketScrollAnchor?>(null) }
    var session by rememberSaveable(stateSaver = MarketLinkSessionSaver) { mutableStateOf(MarketLinkSession()) }
    val showSignals = (session.viewOverride ?: signalsState.preferences.view) == MarketView.SignalsV2
    val onlySignalCells = signalsState.preferences.onlySignalCells
    val focusGroups = remember(signalsState.presentation.signals, signalsState.presentation.status, signalsState.snapshot, state.symbols) {
        signalFocusGroups(signalsState, state.symbols)
    }
    var expandedFocusSymbols by rememberSaveable(state.settings.baseUrl) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(focusGroups.map { it.symbol }) {
        expandedFocusSymbols = expandedFocusSymbols.filter { symbol -> focusGroups.any { it.symbol == symbol } }
    }
    val liveLink = resolveLiveMarketLink(session.selection, signalsState)
    var klineFromSignals by rememberSaveable { mutableStateOf(false) }
    val windowSize = LocalWindowInfo.current.containerSize
    val compactHeader = windowSize.width > windowSize.height
    val compactFilters = compactHeader && windowSize.height / LocalDensity.current.density < 360f
    val tableLayout = resolveTableLayout(state.settings.layoutMode, compactHeader)
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedCell by remember { mutableStateOf<GuailiCell?>(null) }
    var selectedCellFromSignals by remember { mutableStateOf(false) }
    var selectedCellContext by remember { mutableStateOf<String?>(null) }
    var showDataStatus by rememberSaveable { mutableStateOf(false) }
    var klineSymbol by rememberSaveable { mutableStateOf<String?>(null) }
    var klineInterval by rememberSaveable { mutableStateOf<String?>(null) }
    var klineAlertId by rememberSaveable { mutableStateOf<Long?>(null) }
    var alertListSymbol by rememberSaveable(state.settings.baseUrl) { mutableStateOf<String?>(null) }
    var tvListSymbol by rememberSaveable(state.settings.baseUrl) { mutableStateOf<String?>(null) }
    var tvListInterval by rememberSaveable(state.settings.baseUrl) { mutableStateOf<String?>(null) }
    val klineTarget = klineSymbol?.let { symbol ->
        klineInterval?.let { interval -> KlineTarget(symbol, interval) }
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var intervalGroup by rememberSaveable { mutableStateOf(IntervalGroup.All) }
    val filteredIntervals = filterIntervals(state.intervals, intervalGroup)
    val visibleIntervals = linkedVisibleIntervals(filteredIntervals, state.intervals, liveLink?.members?.keys.orEmpty(), session.reveal)
    val summaries = marketSignalSummaries(signalsState, state.symbols)
    val density = LocalDensity.current
    var tableWidthPixels by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var locationHandled by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }

    fun currentOrigin() = MarketLinkOrigin(
        view = if (showSignals) MarketView.SignalsV2 else MarketView.Table,
        selection = session.selection, reveal = session.reveal, scopeSymbol = session.scopeSymbol,
        scopeInterval = session.scopeInterval, narrowPaneOpen = session.narrowPaneOpen,
        intervalGroup = intervalGroup.name, layoutMode = state.settings.layoutMode.name,
        cellSymbol = selectedCell?.symbol, cellInterval = selectedCell?.interval,
        table = tableListState.marketAnchor(), groups = groupListState.marketAnchor(),
        signals = signalListState.marketAnchor(), pane = paneListState.marketAnchor(),
        horizontalInterval = visibleIntervals.getOrNull(tableHorizontal.value / with(density) { tableDimensions(state.settings.tableDensity, density.fontScale).cellWidth.roundToPx() }.coerceAtLeast(1)),
        horizontalOffset = tableHorizontal.value % with(density) { tableDimensions(state.settings.tableDensity, density.fontScale).cellWidth.roundToPx() }.coerceAtLeast(1),
        onlySignalCells = onlySignalCells, focus = focusListState.marketAnchor(), cellFromSignals = selectedCellFromSignals,
    )
    fun selectCell(cell: GuailiCell, fromSignals: Boolean = false) {
        selectedCell = cell
        selectedCellFromSignals = fromSignals
        selectedCellContext = if (fromSignals) signalFocusCellContext(signalsState) else null
    }
    fun selectStructure(item: ServerSignalItem) {
        session = session.push(currentOrigin()).copy(selection = MarketLinkKey(item.symbol, item.signal.id),
            viewOverride = MarketView.Table, reveal = false, narrowPaneOpen = false, locationRequest = session.locationRequest + 1)
        selectedCell = null
    }
    fun showSymbolSignals(symbol: String, interval: String? = null) {
        session = session.push(currentOrigin()).copy(scopeSymbol = symbol, scopeInterval = interval, narrowPaneOpen = true)
        selectedCell = null
        scope.launch { paneListState.scrollToItem(0) }
    }
    fun clearLink() {
        session = MarketLinkSession(baseUrl = session.baseUrl)
        selectedCell = null
    }
    fun returnFromLink() {
        val origin = session.origins.lastOrNull() ?: return
        session = session.copy(viewOverride = origin.view, selection = origin.selection, reveal = origin.reveal,
            scopeSymbol = origin.scopeSymbol, scopeInterval = origin.scopeInterval, narrowPaneOpen = origin.narrowPaneOpen,
            origins = session.origins.dropLast(1))
        intervalGroup = IntervalGroup.valueOf(origin.intervalGroup)
        viewModel.setLayoutMode(LayoutMode.valueOf(origin.layoutMode))
        signalsViewModel.setOnlySignalCells(origin.onlySignalCells)
        val restoredCell = if (origin.cellFromSignals) focusGroups.firstOrNull { it.symbol == origin.cellSymbol }
            ?.rows?.flatMap { it.members }?.firstOrNull { it.cell.interval == origin.cellInterval }?.cell
            else state.cells[origin.cellSymbol]?.get(origin.cellInterval)
        selectedCell = null
        restoredCell?.let { selectCell(it, origin.cellFromSignals) }
        signalRestore = origin.signals
        paneRestore = origin.pane
        locationHandled = session.locationRequest
        scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            tableListState.restoreMarketAnchor(origin.table, state.symbols)
            groupListState.restoreMarketAnchor(origin.groups, state.symbols)
            focusListState.restoreMarketAnchor(origin.focus, focusGroups.map { it.symbol })
            val restored = linkedVisibleIntervals(filterIntervals(state.intervals, intervalGroup), state.intervals,
                resolveLiveMarketLink(origin.selection, signalsState)?.members?.keys.orEmpty(), origin.reveal)
            val cellWidth = with(density) { tableDimensions(state.settings.tableDensity, density.fontScale).cellWidth.roundToPx() }
            val index = restored.indexOf(origin.horizontalInterval).coerceAtLeast(0)
            tableHorizontal.scrollTo(index * cellWidth + origin.horizontalOffset)
        }
    }
    fun openSignalKline(item: ServerSignalItem) {
        klineAlertId = null
        klineFromSignals = true
        klineSymbol = item.symbol
        klineInterval = item.signal.anchorInterval
    }
    fun openPriceAlert(alert: com.gouge.guaili.data.PriceAlertDto) {
        alertsViewModel.acknowledge(listOf(alert))
        klineFromSignals = false
        klineSymbol = alert.symbol
        klineInterval = alert.interval
        klineAlertId = alert.id
        alertListSymbol = null
        selectedCell = null
    }
    fun openPeriodAlert(symbol: String, interval: String) {
        alertsState.preferredAlert(symbol, interval)?.let(::openPriceAlert)
    }
    fun openSymbolAlerts(symbol: String) {
        alertListSymbol = symbol
        alertsViewModel.refresh()
    }
    fun openTvAlerts(symbol: String, interval: String? = null) {
        tvListSymbol = symbol
        tvListInterval = interval
        xbotViewModel.loadAlerts(force = true)
    }
    fun openTvManager(entry: MarketTvAlertEntry?) {
        selectedCell = null
        tvListSymbol = null
        onOpenTvAlerts(entry)
    }
    fun saveTvTicker(symbol: String, ticker: String?): String? = try {
        tvTickers = tvTickerStore.save(symbol, ticker)
        null
    } catch (error: Exception) {
        error.message ?: "品种映射保存失败，请重试"
    }

    LaunchedEffect(state.settings.baseUrl, state.settingsLoaded) {
        if (!state.settingsLoaded) return@LaunchedEffect
        val source = normalizeServerSignalsBaseUrl(state.settings.baseUrl)
        if (session.baseUrl != source) {
            session = MarketLinkSession(baseUrl = source)
            locationHandled = 0
        }
    }
    LaunchedEffect(session.selection?.symbol, session.reveal, liveLink?.members?.keys, state.settings.baseUrl, state.settingsLoaded) {
        if (!state.settingsLoaded) return@LaunchedEffect
        viewModel.setTemporaryRange(session.selection?.symbol,
            if (session.reveal) liveLink?.members?.keys.orEmpty().toList() else emptyList())
    }
    LaunchedEffect(session.locationRequest, state.symbols, visibleIntervals, showSignals, tableLayout, tableWidthPixels, onlySignalCells, focusGroups) {
        val link = liveLink
        if (showSignals || link == null || session.locationRequest == locationHandled || tableWidthPixels == 0) return@LaunchedEffect
        val symbolIndex = state.symbols.indexOf(link.key.symbol)
        if (symbolIndex < 0) return@LaunchedEffect
        if (onlySignalCells) {
            val index = focusGroups.indexOfFirst { it.symbol == link.key.symbol }
            if (index >= 0) focusListState.scrollToItem(index)
        } else if (tableLayout == TableLayout.Table) {
            tableListState.scrollToItem(symbolIndex)
            val period = visibleIntervals.indexOfFirst { it in link.members }.coerceAtLeast(0)
            tableHorizontal.scrollTo(period * with(density) { tableDimensions(state.settings.tableDensity, density.fontScale).cellWidth.roundToPx() })
        } else {
            val dimensions = groupedLayoutDimensions((tableWidthPixels / density.density).toInt(), state.settings.groupLayoutSize,
                state.settings.tableDensity, density.fontScale)
            val period = visibleIntervals.indexOfFirst { it in link.members }.coerceAtLeast(0)
            val offset = if (period / dimensions.columns == 0) 0 else with(density) {
                (dimensions.symbolHeaderHeight + (dimensions.periodHeaderHeight + dimensions.table.cellHeight + dimensions.rowPadding * 2) * (period / dimensions.columns)).roundToPx()
            }
            groupListState.scrollToItem(symbolIndex, offset)
        }
        locationHandled = session.locationRequest
    }
    BackHandler(enabled = klineTarget == null && !showSettings && !showHelp && selectedCell == null &&
        (session.origins.isNotEmpty() || session.narrowPaneOpen || session.selection != null)) {
        if (session.origins.isNotEmpty()) returnFromLink() else clearLink()
    }

    LaunchedEffect(requestedKlineTarget) {
        requestedKlineTarget?.let { target ->
            klineFromSignals = false
            klineAlertId = null
            klineSymbol = target.symbol
            klineInterval = target.interval
            onRequestedKlineConsumed()
        }
    }

    val contentActive = klineTarget == null && !showSettings && !showHelp
    LaunchedEffect(lifecycleOwner, xbotViewModel, tvState.isAuthenticated, tvState.serverUrl, contentActive, showSignals) {
        if (!contentActive || showSignals || !tvState.isAuthenticated) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            xbotViewModel.loadAlerts(force = true)
            while (true) {
                tvNow = Instant.now()
                delay(1_000)
            }
        }
    }
    DisposableEffect(lifecycleOwner, alertsViewModel, contentActive, showSignals, state.settingsLoaded) {
        fun update() = alertsViewModel.setForeground(state.settingsLoaded && contentActive && !showSignals &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycleOwner.lifecycle.addObserver(observer)
        update()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); alertsViewModel.setForeground(false) }
    }
    val needsTable = !onlySignalCells || expandedFocusSymbols.isNotEmpty()
    DisposableEffect(lifecycleOwner, viewModel, signalsViewModel, contentActive, showSignals, needsTable) {
        fun update(active: Boolean) {
            viewModel.setForeground(active && contentActive && !showSignals && needsTable)
            signalsViewModel.setForeground(active && contentActive)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> update(true)
                Lifecycle.Event.ON_STOP -> update(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        update(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
            signalsViewModel.setForeground(false)
        }
    }

    klineTarget?.let { target ->
        KlineScreen(
            baseUrl = state.settings.baseUrl,
            symbols = if (klineFromSignals) (signalsState.availableSymbols + target.symbol).distinct() else (state.settings.symbols + target.symbol).distinct(),
            intervals = if (klineFromSignals) (signalsState.snapshot?.response?.results
                ?.firstOrNull { it.symbol == target.symbol }?.perIntervalQuality.orEmpty().map { it.interval } +
                state.settings.intervals + target.interval).distinct() else (state.settings.intervals + target.interval).distinct(),
            initialSymbol = target.symbol,
            initialInterval = target.interval,
            initialAlertId = klineAlertId,
            refreshSeconds = state.settings.autoRefreshSeconds,
            closedOnly = if (klineFromSignals) false else state.settings.closedOnly,
            onBack = {
                klineSymbol = null
                klineInterval = null
                klineAlertId = null
            },
        )
        return
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            onSave = { settings ->
                viewModel.saveSettingsAndAwait(settings)
                scope.launch { snackbarHostState.showSnackbar("Settings saved") }
            },
            onDismiss = { showSettings = false },
        )
        return
    }

    if (showHelp) {
        GuailiSignalHelpScreen(onBack = { showHelp = false })
        return
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp)) {
            val wide = maxWidth / density.fontScale.coerceAtLeast(1f) >= 900.dp && maxHeight >= 400.dp
            val bottomPaneHeight = (maxHeight * 0.42f).coerceAtMost(360.dp)
            val paneOpen = !showSignals && if (wide) session.narrowPaneOpen || !signalsState.preferences.widePaneCollapsed else session.narrowPaneOpen
            val signalContent: @Composable (Boolean) -> Unit = { embedded ->
                signalContentHolder.SaveableStateProvider(if (embedded) "embedded" else "standalone") {
                    Column(Modifier.fillMaxSize()) {
                    if (embedded && session.origins.isNotEmpty() && liveLink == null) TextButton(
                        onClick = ::returnFromLink, modifier = Modifier.testTag("market-pane-return"),
                    ) { Text("返回定位前") }
                    MarketSignalsContent(
                        state = signalsState, listState = if (embedded) paneListState else signalListState,
                        onRefresh = signalsViewModel::refresh, onToggleSymbol = signalsViewModel::toggleSymbol,
                        onSelectAll = signalsViewModel::selectAllSymbols, onToggleKind = signalsViewModel::toggleKind,
                        onOpenSettings = { onOpenAppSettings?.invoke() ?: run { showSettings = true } },
                        onOpenKline = { target -> klineAlertId = null; klineFromSignals = true; klineSymbol = target.symbol; klineInterval = target.interval },
                        onLocateTable = ::selectStructure, embedded = embedded, selectedKey = session.selection,
                        scopeSymbol = if (embedded) session.scopeSymbol else null,
                        scopeInterval = if (embedded) session.scopeInterval else null,
                        onClearScope = { session = session.copy(scopeSymbol = null, scopeInterval = null); scope.launch { paneListState.scrollToItem(0) } },
                        restoreAnchor = if (embedded) paneRestore else signalRestore,
                        onAnchorRestored = { if (embedded) paneRestore = null else signalRestore = null },
                        modifier = Modifier.weight(1f),
                    )
                    }
                }
            }
            val tableContent: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize().onSizeChanged { tableWidthPixels = it.width }) {
                    Toolbar(state, compactHeader, compactFilters, intervalGroup, { intervalGroup = it },
                        { if (!onlySignalCells || expandedFocusSymbols.isNotEmpty()) viewModel.refresh()
                            signalsViewModel.refresh(); alertsViewModel.refresh(); xbotViewModel.loadAlerts(force = true) }, tableLayout,
                        { viewModel.setLayoutMode(if (tableLayout == TableLayout.Table) LayoutMode.Groups else LayoutMode.Table) },
                        { onOpenAppSettings?.invoke() ?: run { showSettings = true } }, { showHelp = true },
                        onToggleSignalPane = {
                            if (wide) {
                                session = session.copy(narrowPaneOpen = false)
                                signalsViewModel.setWidePaneCollapsed(paneOpen)
                            } else {
                                session = session.copy(narrowPaneOpen = !paneOpen)
                            }
                        },
                        signalPaneOpen = paneOpen,
                        signalsState = signalsState, onlySignalCells = onlySignalCells,
                        onOnlySignalCells = { enabled ->
                            expandedFocusSymbols = emptyList()
                            signalsViewModel.setOnlySignalCells(enabled)
                        },
                        onStatusClick = { showDataStatus = true },
                    )
                    MarketTvSyncBar(tvState) { openTvManager(null) }
                    if (!onlySignalCells) state.errorMessage?.let { ErrorBanner(it, state.cells.isNotEmpty(), viewModel::refresh) }
                    liveLink?.let { link ->
                        MarketLinkBar(link, signalsState, state, filteredIntervals, session.reveal, session.origins.isNotEmpty(),
                            { session = session.copy(reveal = !session.reveal, locationRequest = session.locationRequest + 1) },
                            { showSymbolSignals(link.key.symbol) }, { link.item?.let(::openSignalKline) }, ::clearLink, ::returnFromLink)
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp)) {
                        if (onlySignalCells) SignalFocusTable(focusGroups, state, signalsState, focusListState,
                            expandedFocusSymbols, { symbol -> expandedFocusSymbols =
                                if (symbol in expandedFocusSymbols) expandedFocusSymbols - symbol else expandedFocusSymbols + symbol },
                            { selectCell(it, true) }, { selectCell(it) }, ::selectStructure,
                            alertSummaries, alertsState.error != null, ::openSymbolAlerts, ::openPeriodAlert, Modifier.fillMaxSize(), session.selection,
                            tvAlerts = tvSummaries, onSymbolTvAlerts = { openTvAlerts(it) },
                            onPeriodTvAlerts = { symbol, interval -> openTvAlerts(symbol, interval) })
                        else when (tableLayout) {
                            TableLayout.Table -> GuailiTable(state, { selectCell(it) }, Modifier.fillMaxSize(), visibleIntervals,
                                tableListState, tableHorizontal, summaries, liveLink, ::selectStructure, { showSymbolSignals(it) },
                                alertSummaries, alertsState.error != null, ::openSymbolAlerts, ::openPeriodAlert,
                                tvAlerts = tvSummaries, onSymbolTvAlerts = { openTvAlerts(it) },
                                onPeriodTvAlerts = { symbol, interval -> openTvAlerts(symbol, interval) })
                            TableLayout.Groups -> GuailiGroupedTable(state, { selectCell(it) }, Modifier.fillMaxSize(), visibleIntervals,
                                groupListState, summaries, liveLink, ::selectStructure, { showSymbolSignals(it) },
                                alertSummaries, alertsState.error != null, ::openSymbolAlerts, ::openPeriodAlert,
                                tvAlerts = tvSummaries, onSymbolTvAlerts = { openTvAlerts(it) },
                                onPeriodTvAlerts = { symbol, interval -> openTvAlerts(symbol, interval) })
                        }
                        if (!onlySignalCells && state.isLoading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        if (if (onlySignalCells) signalsState.isRefreshing else state.isRefreshing)
                            LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                }
            }
            Column(Modifier.fillMaxSize()) {
                MarketViewSelector(if (showSignals) MarketView.SignalsV2 else MarketView.Table) { view ->
                    session = session.copy(viewOverride = view)
                    signalsViewModel.setView(view)
                }
                if (showSignals) Box(Modifier.weight(1f)) { signalContent(false) }
                else if (wide && paneOpen) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width((320 * density.fontScale.coerceAtLeast(1f)).dp).fillMaxSize().testTag("market-signal-pane-wide")) { signalContent(true) }
                    Box(Modifier.weight(1f)) { tableContent() }
                } else {
                    Box(Modifier.weight(1f)) { tableContent() }
                    if (paneOpen) Box(Modifier.fillMaxWidth().height(bottomPaneHeight)
                        .testTag("market-signal-pane-bottom")) { signalContent(true) }
                }
            }
        }
    }

    selectedCell?.let { cell ->
        val periodAlerts = alertSummaries[cell.symbol]?.periods?.get(cell.interval)
        val waitingReasons = alertsState.alerts.filter {
            it.symbol == cell.symbol && it.interval == cell.interval && it.status == "active" &&
                (it.expiresAt == null || it.expiresAt > alertsState.now) && it.dataStatus != "live"
        }.map { it.stateLabel(alertsState.now) }.distinct().joinToString("、")
        CellDetailSheet(
            cell = cell,
            onOpenKline = {
                klineFromSignals = selectedCellFromSignals
                val alert = alertsState.preferredAlert(cell.symbol, cell.interval)
                klineAlertId = alert?.id
                alert?.let { alertsViewModel.acknowledge(listOf(it)) }
                klineSymbol = cell.symbol
                klineInterval = cell.interval
                selectedCell = null
            },
            onDismiss = { selectedCell = null },
            onViewSignals = { showSymbolSignals(cell.symbol, cell.interval) },
            priceAlertDescription = periodAlerts?.description?.let { it + if (waitingReasons.isBlank()) "" else "（$waitingReasons）" },
            dataContext = selectedCellContext,
            tvAlertsContent = {
                MarketTvAlertsSection(cell.symbol, cell.interval, tvTickers[cell.symbol] ?: defaultMarketTvTicker(cell.symbol),
                    tvState, tvNow, onRefresh = { xbotViewModel.loadAlerts(force = true) }, onManage = ::openTvManager,
                    onReset = { xbotViewModel.resetTvAlert(it.config, it.alert) },
                    onSaveTicker = { saveTvTicker(cell.symbol, it) })
            },
        )
    }

    if (showDataStatus) MarketDataStatusSheet(state, signalsState, onlySignalCells, focusGroups,
        onRefresh = { if (!onlySignalCells) viewModel.refresh(); signalsViewModel.refresh() },
        onDismiss = { showDataStatus = false })

    alertListSymbol?.let { symbol ->
        val symbolAlerts = alertsState.alerts.filter { it.symbol == symbol }.sortedWith(
            compareByDescending<com.gouge.guaili.data.PriceAlertDto> { it.status == "triggered" }
                .thenByDescending { it.triggeredAt ?: 0L }.thenByDescending { it.id })
        // Acknowledge exactly the snapshot shown, so a later trigger remains unread.
        LaunchedEffect(symbol, symbolAlerts.map { it.triggerKey() }) { alertsViewModel.acknowledge(symbolAlerts) }
        PriceAlertListSheet(symbolAlerts, emptyMap(), alertsState.error ?: alertsState.seenError,
            onDismiss = { alertListSymbol = null }, onSelect = { id -> symbolAlerts.firstOrNull { it.id == id }?.let(::openPriceAlert) },
            onRefresh = { alertsViewModel.refresh(); alertsViewModel.acknowledge(symbolAlerts) },
            title = "${symbol.removeSuffix("USDT")} · 价格警报", loading = alertsState.loading)
    }

    tvListSymbol?.let { symbol ->
        MarketTvAlertSheet(symbol, tvTickers[symbol] ?: defaultMarketTvTicker(symbol), tvState, tvNow,
            onDismiss = { tvListSymbol = null }, onRefresh = { xbotViewModel.loadAlerts(force = true) },
            onManage = ::openTvManager, onReset = { xbotViewModel.resetTvAlert(it.config, it.alert) },
            onSaveTicker = { saveTvTicker(symbol, it) }, interval = tvListInterval)
    }

}

data class KlineTarget(
    val symbol: String,
    val interval: String,
)

@Composable
private fun Toolbar(
    state: GuailiTableState,
    compact: Boolean,
    compactFilters: Boolean,
    intervalGroup: IntervalGroup,
    onSelectIntervalGroup: (IntervalGroup) -> Unit,
    onRefresh: () -> Unit,
    tableLayout: TableLayout,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onToggleSignalPane: () -> Unit,
    signalPaneOpen: Boolean,
    signalsState: MarketSignalsUiState,
    onlySignalCells: Boolean,
    onOnlySignalCells: (Boolean) -> Unit,
    onStatusClick: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showTitle = maxWidth.value / LocalDensity.current.fontScale >= 360f
        Column(Modifier.fillMaxWidth().padding(top = if (compact) 2.dp else 6.dp)) {
            Row(Modifier.fillMaxWidth().testTag("market-toolbar-row"), verticalAlignment = Alignment.CenterVertically) {
                if (showTitle) {
                    Text("Guaili", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, modifier = Modifier.padding(end = 6.dp))
                }
                StatusIndicator(state, signalsState, onlySignalCells, onStatusClick, Modifier.weight(1f))
                ToolbarActions(onRefresh, tableLayout, onToggleLayout, onOpenSettings, onOpenHelp,
                    onToggleSignalPane, signalPaneOpen, onlySignalCells,
                    if (onlySignalCells) signalsState.isRefreshing else state.isLoading || state.isRefreshing)
            }
            Row(Modifier.fillMaxWidth().testTag("market-display-controls"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.height(48.dp).testTag("market-signal-focus-toggle")
                    .toggleable(value = onlySignalCells, role = Role.Checkbox, onValueChange = onOnlySignalCells)
                    .semantics { contentDescription = "仅显示 V2 信号格" }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = onlySignalCells, onCheckedChange = null, modifier = Modifier.size(32.dp))
                    Text("仅V2信号格", fontSize = 12.sp, maxLines = 1)
                }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onlySignalCells) Text(signalFocusSourceLabel(signalsState), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("market-focus-source"))
                else if (compactFilters) CompactIntervalFilter(intervalGroup, onSelectIntervalGroup)
                else IntervalGroup.entries.forEach { group -> FilterChip(selected = intervalGroup == group,
                    onClick = { onSelectIntervalGroup(group) }, label = { Text(group.label) }) }
                }
            }
        }
    }
}

@Composable
private fun ToolbarActions(
    onRefresh: () -> Unit,
    tableLayout: TableLayout,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onToggleSignalPane: () -> Unit,
    signalPaneOpen: Boolean,
    onlySignalCells: Boolean,
    refreshBusy: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleLayout, enabled = !onlySignalCells) {
            Icon(
                imageVector = when (tableLayout) {
                    TableLayout.Table -> Icons.Outlined.GridView
                    TableLayout.Groups -> Icons.AutoMirrored.Outlined.ViewList
                },
                contentDescription = when (tableLayout) {
                    TableLayout.Table -> "Switch to symbol groups"
                    TableLayout.Groups -> "Switch to compact table"
                },
            )
        }
        IconButton(onClick = onOpenHelp) {
            Icon(Icons.Outlined.Info, contentDescription = "乖离信号帮助")
        }
        IconButton(
            onClick = onToggleSignalPane,
            modifier = Modifier.testTag("market-signal-pane-toggle"),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ShowChart,
                contentDescription = if (signalPaneOpen) "收起信号栏" else "展开信号栏",
            )
        }
        IconButton(
            onClick = onRefresh,
            enabled = !refreshBusy,
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun StatusIndicator(
    state: GuailiTableState,
    signals: MarketSignalsUiState,
    onlySignalCells: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (!onlySignalCells) statusColor(state) else when {
        signals.presentation.status == "ready" && signals.refreshError == null -> Color(0xFF22C55E)
        signals.isRefreshing -> MaterialTheme.colorScheme.primary
        else -> Color(0xFFF59E0B)
    }
    Row(modifier.height(48.dp).testTag("market-data-status")
        .clickable(role = Role.Button, onClickLabel = "查看数据状态详情", onClick = onClick)
        .semantics { contentDescription = "数据状态：${marketDataStatusLabel(state, signals, onlySignalCells)}，点击查看详情" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(marketDataStatusLabel(state, signals, onlySignalCells), color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompactIntervalFilter(selected: IntervalGroup, onSelected: (IntervalGroup) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = "Period filter: ${selected.label}" },
        ) {
            Text(selected.label)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            IntervalGroup.entries.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.label) },
                    onClick = { onSelected(group); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    hasCachedData: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                text = if (hasCachedData) "Showing cached data. $message" else message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

internal enum class IntervalGroup(val label: String) {
    All("All"),
    Short("Short"),
    Medium("Medium"),
    Long("Long"),
}

internal fun filterIntervals(intervals: List<String>, group: IntervalGroup): List<String> {
    if (group == IntervalGroup.All) return intervals

    return intervals.filter { interval ->
        val minutes = intervalMinutes(interval)
        when (group) {
            IntervalGroup.All -> true
            IntervalGroup.Short -> minutes != null && minutes <= 15.0
            IntervalGroup.Medium -> minutes != null && minutes > 15.0 && minutes <= 240.0
            IntervalGroup.Long -> minutes == null || minutes > 240.0
        }
    }
}

private fun intervalMinutes(interval: String): Double? {
    val normalized = interval.trim().uppercase()
    val multiplier = when (normalized.lastOrNull()) {
        'S' -> 1.0 / 60.0
        'D' -> 24.0 * 60.0
        'W' -> 7.0 * 24.0 * 60.0
        else -> return normalized.toDoubleOrNull()
    }
    val value = normalized.dropLast(1).toDoubleOrNull() ?: return null
    return value * multiplier
}

@Composable
private fun statusColor(state: GuailiTableState): Color = when {
    state.isStale || state.errorMessage != null || state.lastUpdatedAt == null -> Color(0xFFF59E0B)
    state.isLoading || state.isRefreshing -> MaterialTheme.colorScheme.primary
    else -> Color(0xFF22C55E)
}

private val MarketLinkSessionSaver = Saver<MarketLinkSession, String>(
    save = { Json.encodeToString(it) },
    restore = { runCatching { Json.decodeFromString<MarketLinkSession>(it) }.getOrNull() },
)

private fun LazyListState.marketAnchor(): MarketScrollAnchor = MarketScrollAnchor(
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex }?.key?.toString(),
    firstVisibleItemIndex, firstVisibleItemScrollOffset,
)

private suspend fun LazyListState.restoreMarketAnchor(anchor: MarketScrollAnchor, keys: List<String>? = null) {
    val total = keys?.size ?: layoutInfo.totalItemsCount
    if (total == 0) return
    val index = keys?.indexOf(anchor.key)?.takeIf { it >= 0 }
        ?: layoutInfo.visibleItemsInfo.firstOrNull { it.key.toString() == anchor.key }?.index
        ?: anchor.index
    scrollToItem(index.coerceIn(0, total - 1), anchor.offset)
}
