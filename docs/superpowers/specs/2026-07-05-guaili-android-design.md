# Android Guaili Table Client Design

Date: 2026-07-05

## Goal

Build a native Android client for viewing guaili indicator values from the existing HTTP backend. The app should make it easy to scan about 10 symbols across more than 20 intervals, roughly 200 to 300 cells, with fast refresh and clear color semantics.

The first release focuses on the latest guaili value table. Historical charts, alerts, and advanced signal workflows are deferred until the table experience is stable.

## Backend API

The app will call:

```http
GET /api/indicators/guaili
```

Primary query parameters:

- `symbols`: comma-separated trading pairs, for example `BTCUSDT,ETHUSDT`.
- `intervals`: comma-separated intervals, for example `1,5,15,60,D`.
- `limit`: use `1` for the main table because only the latest value is displayed.
- `calcLimit`: default to `500` so MA and ATR calculations still have enough history.
- `closedOnly`: default to `false` so the current unfinished candle can be shown.
- Indicator settings: `maLength`, `maType`, `atrLen`, `atrPercentLen`, `maxAtrRank`, `slopeMul`, `useSlope`.

The app should send all selected symbols and intervals in one request whenever possible. This avoids 200 individual calls for a 10 x 20 table.

## Recommended Stack

- Kotlin
- Jetpack Compose
- MVVM
- Retrofit + OkHttp
- Kotlinx Serialization
- Kotlin Coroutines and Flow
- Android DataStore for saved settings

This stack keeps the app native, lightweight, and maintainable. Compose is a good fit because the table cells need dynamic color and compact interaction states.

## Main User Experience

The first screen is the table itself, not a landing page.

Layout:

- Top compact toolbar with refresh, auto-refresh state, settings, and last update time.
- Matrix body with symbols as rows and intervals as columns.
- Sticky symbol column so the user can still see the symbol while horizontally scrolling.
- Sticky interval header so the user can still see the interval while vertically scrolling.
- Bottom or top status strip for loading, error, and stale-data state.

Default table shape:

```text
        1m    2m    3m    5m    10m   15m   30m   1h    4h    1D
BTC     12    -3     0     8     15    -6     4     9     18    -2
ETH      4    -8    -11    2      0     7     5    -4     12     3
```

For the expected 10 symbols and 20+ intervals, the app should not use cards for each symbol. It should use a dense matrix optimized for scanning.

## Table Interaction

Cells display `latest.value`, where the backend value is `int(guaili * 10)`.

Cell states:

- Positive value: green gradient.
- Negative value: red gradient.
- Near zero: neutral gray or white.
- `isClosed=false`: subtle marker to show the value belongs to an unfinished candle.
- `longTrend=true`: optional upward marker.
- `shortTrend=true`: optional downward marker.
- `rankFilter=false`: optional dimmed state because the ATR rank filter failed.

Tapping a cell opens a compact detail sheet:

- symbol
- interval
- value
- guaili
- ma
- atr14
- atrRank
- rankFilter
- longTrend
- shortTrend
- isClosed
- openTime
- closeTime

## Color Semantics

The app should approximate the Pine Script table color function:

```text
value < 0: red gradient toward neutral
value = 0: neutral
value > 0: neutral toward green gradient
```

Use `20` as the default maximum color-strength reference, matching the script's `f_getBgColor(value, 20)` usage. Values beyond `-20` or `20` should clamp to the strongest color.

## Settings

The settings screen should be practical and compact:

- Backend base URL
- Symbols list
- Intervals list
- Auto-refresh interval
- `closedOnly`
- `calcLimit`
- `maLength`
- `maType`
- `atrLen`
- `atrPercentLen`
- `maxAtrRank`
- `slopeMul`
- `useSlope`

Saved defaults:

- `limit=1`
- `calcLimit=500`
- `closedOnly=false`
- `maLength=20`
- `maType=EMA`
- `atrLen=1`
- `atrPercentLen=20`
- `maxAtrRank=100`
- `slopeMul=0.1`
- `useSlope=true`

Intervals should support the backend's existing notation, including minute values like `1`, `5`, `15`, higher periods like `60`, `240`, `D`, and second-level periods if the backend exposes them.

## Data Model

Network DTOs should mirror the API response closely. The ViewModel should convert them into a table-friendly model:

```kotlin
data class GuailiCell(
    val symbol: String,
    val interval: String,
    val value: Int?,
    val guaili: Double?,
    val ma: Double?,
    val atr14: Double?,
    val atrRank: Double?,
    val rankFilter: Boolean?,
    val longTrend: Boolean?,
    val shortTrend: Boolean?,
    val isClosed: Boolean?,
    val openTime: String?,
    val closeTime: String?
)
```

The ViewModel should expose:

```kotlin
data class GuailiTableState(
    val symbols: List<String>,
    val intervals: List<String>,
    val cells: Map<String, Map<String, GuailiCell>>,
    val isLoading: Boolean,
    val isRefreshing: Boolean,
    val lastUpdatedAt: Long?,
    val errorMessage: String?,
    val isStale: Boolean
)
```

The nested map lets the UI render by symbol and interval without repeatedly searching the API response.

## Refresh Strategy

Use polling for the first release.

- Default auto-refresh interval: 5 seconds.
- Manual refresh button always available.
- Refresh only while the app is visible.
- Keep the last successful table visible after an error.
- Mark data stale if refresh fails or if the last update exceeds the configured interval by a meaningful margin.
- Use one batched request per refresh cycle.
- Add simple retry/backoff after failures, such as 5s, 10s, then 30s.

This is enough for the current HTTP API. WebSocket or SSE can be evaluated later if the backend adds push updates.

## Performance Notes

The app must handle at least:

- 10 symbols
- 20 to 30 intervals
- 200 to 300 cells
- 3 to 5 second refresh cycles

Implementation guidance:

- Avoid per-cell network calls.
- Keep cell composables small and stable.
- Use stable keys for rows and cells.
- Compute color values outside of expensive recomposition paths where practical.
- Preserve previous data while a refresh is in flight to avoid visual flicker.
- Prefer fixed cell dimensions so text changes do not resize the table.
- Avoid embedding each row in decorative cards; this should look like a compact trading tool.

## Error Handling

Expected states:

- Backend unavailable.
- Invalid base URL.
- Timeout.
- Empty response.
- Missing symbol or interval in response.
- Some cells missing latest data.
- JSON parse failure after backend changes.

The UI should show a clear status message but keep the last valid values visible when available.

## Testing Plan

Unit tests:

- Query parameter construction.
- API response parsing.
- Response-to-table mapping.
- Color bucket or gradient clamping.
- Settings serialization and defaults.

ViewModel tests:

- Initial load.
- Manual refresh.
- Auto-refresh lifecycle.
- Error keeps previous data.
- Missing cells produce empty table slots instead of crashes.

UI tests or screenshot checks:

- 10 x 20 table renders.
- Long symbol names do not overlap.
- Horizontal and vertical scrolling work.
- Cell detail sheet opens from a table cell.
- Loading, error, and stale states are visible.

## Deferred Features

- Historical line chart per cell or per symbol.
- Alert rules and Android notifications.
- Symbol groups or watchlists.
- Sorting by strongest positive or negative value.
- Full-screen landscape mode.
- Home-screen widget.
- WebSocket or SSE streaming.

## Recommended First Milestone

Deliver a usable dense table:

1. Create Android project with Kotlin and Compose.
2. Add Retrofit API client and DTOs.
3. Add settings storage with practical defaults.
4. Implement table state mapping.
5. Build the dense matrix UI with sticky headers.
6. Add manual refresh and foreground auto-refresh.
7. Add cell color, unfinished-candle marker, and detail sheet.
8. Add focused tests for parsing, mapping, settings, and refresh behavior.

