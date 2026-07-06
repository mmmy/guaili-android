# Guaili Android Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Android app that displays the latest guaili values for about 10 symbols across 20 to 30 intervals in a dense, refreshable matrix.

**Architecture:** Create a single-module Kotlin Android app using Compose. Keep network DTOs, domain table mapping, settings, ViewModel state, and UI composables in separate focused files so the table can be tested without rendering Android UI.

**Tech Stack:** Kotlin, Jetpack Compose, MVVM, Retrofit, OkHttp, Kotlinx Serialization, Coroutines/Flow, Android DataStore, JUnit.

---

## File Structure

- `settings.gradle.kts`: Gradle plugin repositories and module declaration.
- `build.gradle.kts`: root plugin versions.
- `gradle.properties`: AndroidX, Kotlin, JVM, and build flags.
- `app/build.gradle.kts`: Android app configuration and dependencies.
- `app/src/main/AndroidManifest.xml`: app manifest with internet permission.
- `app/src/main/java/com/gouge/guaili/MainActivity.kt`: Compose entry point.
- `app/src/main/java/com/gouge/guaili/data/GuailiApiService.kt`: Retrofit API declaration.
- `app/src/main/java/com/gouge/guaili/data/GuailiDtos.kt`: serializable response DTOs.
- `app/src/main/java/com/gouge/guaili/data/GuailiRepository.kt`: request construction and repository result wrapper.
- `app/src/main/java/com/gouge/guaili/domain/GuailiModels.kt`: table-friendly domain models.
- `app/src/main/java/com/gouge/guaili/domain/GuailiTableMapper.kt`: API response to matrix mapping.
- `app/src/main/java/com/gouge/guaili/domain/GuailiColors.kt`: value-to-color strength helpers.
- `app/src/main/java/com/gouge/guaili/settings/GuailiSettings.kt`: settings model and defaults.
- `app/src/main/java/com/gouge/guaili/settings/SettingsStore.kt`: DataStore persistence.
- `app/src/main/java/com/gouge/guaili/ui/GuailiViewModel.kt`: table state, refresh, and auto-refresh.
- `app/src/main/java/com/gouge/guaili/ui/GuailiScreen.kt`: main screen layout.
- `app/src/main/java/com/gouge/guaili/ui/GuailiTable.kt`: dense matrix table.
- `app/src/main/java/com/gouge/guaili/ui/CellDetailSheet.kt`: tapped-cell details.
- `app/src/main/java/com/gouge/guaili/ui/SettingsSheet.kt`: compact settings editor.
- `app/src/main/java/com/gouge/guaili/ui/theme/Theme.kt`: Material theme.
- `app/src/test/java/com/gouge/guaili/domain/GuailiTableMapperTest.kt`: response mapping tests.
- `app/src/test/java/com/gouge/guaili/domain/GuailiColorsTest.kt`: color helper tests.
- `app/src/test/java/com/gouge/guaili/settings/GuailiSettingsTest.kt`: defaults and serialization tests.
- `app/src/test/java/com/gouge/guaili/ui/GuailiViewModelTest.kt`: refresh state behavior tests.

The workspace is currently not a git repository. Each task includes verification steps instead of commit steps. If git is initialized later, commit each completed task independently.

## Task 1: Create Android Project Skeleton

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/gouge/guaili/MainActivity.kt`
- Create: `app/src/main/java/com/gouge/guaili/ui/theme/Theme.kt`

- [ ] **Step 1: Add root Gradle settings**

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GuailiAndroid"
include(":app")
```

- [ ] **Step 2: Add root plugin versions**

Create `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10" apply false
}
```

- [ ] **Step 3: Add Gradle properties**

Create `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 4: Add app module build file**

Create `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.gouge.guaili"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gouge.guaili"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.1.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("app.cash.turbine:turbine:1.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

- [ ] **Step 5: Add manifest**

Create `app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:label="Guaili"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 6: Add minimal theme and activity**

Create `app/src/main/java/com/gouge/guaili/ui/theme/Theme.kt`:

```kotlin
package com.gouge.guaili.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    secondary = Color(0xFFA7F3D0),
    background = Color(0xFF101418),
    surface = Color(0xFF151A1F),
    onPrimary = Color(0xFF082F49),
    onSecondary = Color(0xFF064E3B),
    onBackground = Color(0xFFE5E7EB),
    onSurface = Color(0xFFE5E7EB),
)

@Composable
fun GuailiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
```

Create `app/src/main/java/com/gouge/guaili/MainActivity.kt`:

```kotlin
package com.gouge.guaili

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.gouge.guaili.ui.theme.GuailiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GuailiTheme {
                Text("Guaili")
            }
        }
    }
}
```

- [ ] **Step 7: Verify project sync/build**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: Gradle downloads dependencies and `BUILD SUCCESSFUL`. If the wrapper is not present yet, create it before running by using Android Studio's "New Project from Existing Sources" or installing Gradle and running `gradle wrapper --gradle-version 9.4.1`.

## Task 2: Add Network DTOs and API Service

**Files:**
- Create: `app/src/main/java/com/gouge/guaili/data/GuailiDtos.kt`
- Create: `app/src/main/java/com/gouge/guaili/data/GuailiApiService.kt`
- Create: `app/src/test/java/com/gouge/guaili/data/GuailiDtosTest.kt`

- [ ] **Step 1: Write response parsing test**

Create `app/src/test/java/com/gouge/guaili/data/GuailiDtosTest.kt`:

```kotlin
package com.gouge.guaili.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuailiDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesGuailiResponse() {
        val body = """
            {
              "symbols": ["BTCUSDT"],
              "intervals": ["1"],
              "limit": 1,
              "calcLimit": 500,
              "closedOnly": false,
              "timezone": "Asia/Shanghai",
              "serverTime": 1780000000000,
              "results": [
                {
                  "symbol": "BTCUSDT",
                  "series": [
                    {
                      "interval": "1",
                      "count": 1,
                      "latest": {
                        "openTime": "2024-03-10T00:19:00.000+08:00",
                        "closeTime": "2024-03-10T00:19:59.999+08:00",
                        "ma": 100.0,
                        "atr14": 10.0,
                        "atrRank": 50.0,
                        "rankFilter": true,
                        "guaili": 1.2,
                        "value": 12,
                        "longTrend": true,
                        "shortTrend": false,
                        "isClosed": false
                      },
                      "data": []
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString(GuailiResponse.serializer(), body)

        assertEquals(listOf("BTCUSDT"), parsed.symbols)
        assertEquals("BTCUSDT", parsed.results.first().symbol)
        assertEquals("1", parsed.results.first().series.first().interval)
        assertEquals(12, parsed.results.first().series.first().latest?.value)
        assertFalse(parsed.results.first().series.first().latest?.isClosed ?: true)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*GuailiDtosTest"
```

Expected: FAIL because `GuailiResponse` does not exist.

- [ ] **Step 3: Add DTOs**

Create `app/src/main/java/com/gouge/guaili/data/GuailiDtos.kt`:

```kotlin
package com.gouge.guaili.data

import kotlinx.serialization.Serializable

@Serializable
data class GuailiResponse(
    val symbols: List<String> = emptyList(),
    val intervals: List<String> = emptyList(),
    val limit: Int = 0,
    val calcLimit: Int = 0,
    val closedOnly: Boolean = false,
    val timezone: String? = null,
    val serverTime: Long? = null,
    val results: List<GuailiSymbolResult> = emptyList(),
)

@Serializable
data class GuailiSymbolResult(
    val symbol: String,
    val series: List<GuailiSeries> = emptyList(),
)

@Serializable
data class GuailiSeries(
    val interval: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val count: Int = 0,
    val latest: GuailiPoint? = null,
    val data: List<GuailiPoint> = emptyList(),
)

@Serializable
data class GuailiPoint(
    val openTime: String? = null,
    val closeTime: String? = null,
    val ma: Double? = null,
    val atr14: Double? = null,
    val atrRank: Double? = null,
    val rankFilter: Boolean? = null,
    val guaili: Double? = null,
    val value: Int? = null,
    val longTrend: Boolean? = null,
    val shortTrend: Boolean? = null,
    val isClosed: Boolean? = null,
)
```

- [ ] **Step 4: Add Retrofit service**

Create `app/src/main/java/com/gouge/guaili/data/GuailiApiService.kt`:

```kotlin
package com.gouge.guaili.data

import retrofit2.http.GET
import retrofit2.http.Query

interface GuailiApiService {
    @GET("api/indicators/guaili")
    suspend fun getGuaili(
        @Query("symbols") symbols: String,
        @Query("intervals") intervals: String,
        @Query("limit") limit: Int,
        @Query("calcLimit") calcLimit: Int,
        @Query("closedOnly") closedOnly: Boolean,
        @Query("maLength") maLength: Int,
        @Query("maType") maType: String,
        @Query("atrLen") atrLen: Int,
        @Query("atrPercentLen") atrPercentLen: Int,
        @Query("maxAtrRank") maxAtrRank: Double,
        @Query("slopeMul") slopeMul: Double,
        @Query("useSlope") useSlope: Boolean,
    ): GuailiResponse
}
```

- [ ] **Step 5: Run DTO test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*GuailiDtosTest"
```

Expected: PASS.

## Task 3: Add Settings Model

**Files:**
- Create: `app/src/main/java/com/gouge/guaili/settings/GuailiSettings.kt`
- Create: `app/src/test/java/com/gouge/guaili/settings/GuailiSettingsTest.kt`

- [ ] **Step 1: Write settings tests**

Create `app/src/test/java/com/gouge/guaili/settings/GuailiSettingsTest.kt`:

```kotlin
package com.gouge.guaili.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuailiSettingsTest {
    @Test
    fun defaultsMatchFirstRelease() {
        val settings = GuailiSettings.defaults()

        assertEquals(10, settings.symbols.size)
        assertTrue(settings.intervals.size >= 20)
        assertEquals(1, settings.limit)
        assertEquals(500, settings.calcLimit)
        assertFalse(settings.closedOnly)
        assertEquals("EMA", settings.maType)
        assertTrue(settings.useSlope)
    }

    @Test
    fun commaListsTrimBlankEntries() {
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), parseCsv(" BTCUSDT, ,ETHUSDT "))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*GuailiSettingsTest"
```

Expected: FAIL because settings classes do not exist.

- [ ] **Step 3: Add settings model**

Create `app/src/main/java/com/gouge/guaili/settings/GuailiSettings.kt`:

```kotlin
package com.gouge.guaili.settings

data class GuailiSettings(
    val baseUrl: String,
    val symbols: List<String>,
    val intervals: List<String>,
    val autoRefreshSeconds: Int,
    val limit: Int,
    val calcLimit: Int,
    val closedOnly: Boolean,
    val maLength: Int,
    val maType: String,
    val atrLen: Int,
    val atrPercentLen: Int,
    val maxAtrRank: Double,
    val slopeMul: Double,
    val useSlope: Boolean,
) {
    companion object {
        fun defaults(): GuailiSettings = GuailiSettings(
            baseUrl = "http://192.168.1.100:8080/",
            symbols = listOf(
                "BTCUSDT",
                "ETHUSDT",
                "SOLUSDT",
                "BNBUSDT",
                "XRPUSDT",
                "DOGEUSDT",
                "ADAUSDT",
                "AVAXUSDT",
                "LINKUSDT",
                "TRXUSDT",
            ),
            intervals = listOf(
                "1",
                "2",
                "3",
                "5",
                "8",
                "10",
                "15",
                "20",
                "30",
                "45",
                "60",
                "90",
                "120",
                "180",
                "240",
                "360",
                "480",
                "720",
                "D",
                "2D",
                "3D",
                "4D",
                "W",
            ),
            autoRefreshSeconds = 5,
            limit = 1,
            calcLimit = 500,
            closedOnly = false,
            maLength = 20,
            maType = "EMA",
            atrLen = 1,
            atrPercentLen = 20,
            maxAtrRank = 100.0,
            slopeMul = 0.1,
            useSlope = true,
        )
    }
}

fun parseCsv(value: String): List<String> =
    value.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
```

- [ ] **Step 4: Run settings tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*GuailiSettingsTest"
```

Expected: PASS.

## Task 4: Add Domain Mapping and Color Helpers

**Files:**
- Create: `app/src/main/java/com/gouge/guaili/domain/GuailiModels.kt`
- Create: `app/src/main/java/com/gouge/guaili/domain/GuailiTableMapper.kt`
- Create: `app/src/main/java/com/gouge/guaili/domain/GuailiColors.kt`
- Create: `app/src/test/java/com/gouge/guaili/domain/GuailiTableMapperTest.kt`
- Create: `app/src/test/java/com/gouge/guaili/domain/GuailiColorsTest.kt`

- [ ] **Step 1: Write mapper and color tests**

Create `app/src/test/java/com/gouge/guaili/domain/GuailiTableMapperTest.kt`:

```kotlin
package com.gouge.guaili.domain

import com.gouge.guaili.data.GuailiPoint
import com.gouge.guaili.data.GuailiResponse
import com.gouge.guaili.data.GuailiSeries
import com.gouge.guaili.data.GuailiSymbolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuailiTableMapperTest {
    @Test
    fun mapsLatestPointsIntoSymbolIntervalMatrix() {
        val response = GuailiResponse(
            symbols = listOf("BTCUSDT"),
            intervals = listOf("1", "5"),
            results = listOf(
                GuailiSymbolResult(
                    symbol = "BTCUSDT",
                    series = listOf(
                        GuailiSeries(
                            interval = "1",
                            latest = GuailiPoint(value = 12, guaili = 1.2, longTrend = true),
                        ),
                    ),
                ),
            ),
        )

        val table = response.toTable(
            requestedSymbols = listOf("BTCUSDT"),
            requestedIntervals = listOf("1", "5"),
        )

        assertEquals(12, table.cells["BTCUSDT"]?.get("1")?.value)
        assertNull(table.cells["BTCUSDT"]?.get("5"))
    }
}
```

Create `app/src/test/java/com/gouge/guaili/domain/GuailiColorsTest.kt`:

```kotlin
package com.gouge.guaili.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GuailiColorsTest {
    @Test
    fun clampsStrengthAtConfiguredMax() {
        assertEquals(-1.0f, guailiColorStrength(-100, maxAbs = 20))
        assertEquals(-0.5f, guailiColorStrength(-10, maxAbs = 20))
        assertEquals(0.0f, guailiColorStrength(0, maxAbs = 20))
        assertEquals(0.5f, guailiColorStrength(10, maxAbs = 20))
        assertEquals(1.0f, guailiColorStrength(100, maxAbs = 20))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*GuailiTableMapperTest" --tests "*GuailiColorsTest"
```

Expected: FAIL because domain models do not exist.

- [ ] **Step 3: Add domain models**

Create `app/src/main/java/com/gouge/guaili/domain/GuailiModels.kt`:

```kotlin
package com.gouge.guaili.domain

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
    val closeTime: String?,
)

data class GuailiTable(
    val symbols: List<String>,
    val intervals: List<String>,
    val cells: Map<String, Map<String, GuailiCell>>,
)
```

- [ ] **Step 4: Add mapper**

Create `app/src/main/java/com/gouge/guaili/domain/GuailiTableMapper.kt`:

```kotlin
package com.gouge.guaili.domain

import com.gouge.guaili.data.GuailiPoint
import com.gouge.guaili.data.GuailiResponse

fun GuailiResponse.toTable(
    requestedSymbols: List<String>,
    requestedIntervals: List<String>,
): GuailiTable {
    val bySymbol = results.associateBy { it.symbol }
    val cells = requestedSymbols.associateWith { symbol ->
        val seriesByInterval = bySymbol[symbol]?.series.orEmpty().associateBy { it.interval }
        requestedIntervals.mapNotNull { interval ->
            val latest = seriesByInterval[interval]?.latest ?: return@mapNotNull null
            interval to latest.toCell(symbol = symbol, interval = interval)
        }.toMap()
    }

    return GuailiTable(
        symbols = requestedSymbols,
        intervals = requestedIntervals,
        cells = cells,
    )
}

private fun GuailiPoint.toCell(symbol: String, interval: String): GuailiCell =
    GuailiCell(
        symbol = symbol,
        interval = interval,
        value = value,
        guaili = guaili,
        ma = ma,
        atr14 = atr14,
        atrRank = atrRank,
        rankFilter = rankFilter,
        longTrend = longTrend,
        shortTrend = shortTrend,
        isClosed = isClosed,
        openTime = openTime,
        closeTime = closeTime,
    )
```

- [ ] **Step 5: Add color helper**

Create `app/src/main/java/com/gouge/guaili/domain/GuailiColors.kt`:

```kotlin
package com.gouge.guaili.domain

import kotlin.math.max

fun guailiColorStrength(value: Int?, maxAbs: Int = 20): Float {
    if (value == null) return 0.0f
    val divisor = max(1, maxAbs).toFloat()
    return (value / divisor).coerceIn(-1.0f, 1.0f)
}
```

- [ ] **Step 6: Run mapper and color tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*GuailiTableMapperTest" --tests "*GuailiColorsTest"
```

Expected: PASS.

## Task 5: Add Repository and Retrofit Factory

**Files:**
- Create: `app/src/main/java/com/gouge/guaili/data/GuailiRepository.kt`

- [ ] **Step 1: Add repository result and factory**

Create `app/src/main/java/com/gouge/guaili/data/GuailiRepository.kt`:

```kotlin
package com.gouge.guaili.data

import com.gouge.guaili.settings.GuailiSettings
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

sealed interface GuailiResult<out T> {
    data class Success<T>(val value: T) : GuailiResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : GuailiResult<Nothing>
}

class GuailiRepository(
    private val api: GuailiApiService,
) {
    suspend fun fetch(settings: GuailiSettings): GuailiResult<GuailiResponse> =
        try {
            GuailiResult.Success(
                api.getGuaili(
                    symbols = settings.symbols.joinToString(","),
                    intervals = settings.intervals.joinToString(","),
                    limit = settings.limit,
                    calcLimit = settings.calcLimit,
                    closedOnly = settings.closedOnly,
                    maLength = settings.maLength,
                    maType = settings.maType,
                    atrLen = settings.atrLen,
                    atrPercentLen = settings.atrPercentLen,
                    maxAtrRank = settings.maxAtrRank,
                    slopeMul = settings.slopeMul,
                    useSlope = settings.useSlope,
                ),
            )
        } catch (error: Throwable) {
            GuailiResult.Failure(
                message = error.message ?: "Request failed",
                cause = error,
            )
        }

    companion object {
        fun create(baseUrl: String): GuailiRepository {
            val json = Json { ignoreUnknownKeys = true }
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()
            val retrofit = Retrofit.Builder()
                .baseUrl(ensureTrailingSlash(baseUrl))
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()

            return GuailiRepository(retrofit.create(GuailiApiService::class.java))
        }
    }
}

private fun ensureTrailingSlash(baseUrl: String): String =
    if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
```

- [ ] **Step 2: Run compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: PASS.

## Task 6: Add DataStore Settings Persistence

**Files:**
- Create: `app/src/main/java/com/gouge/guaili/settings/SettingsStore.kt`

- [ ] **Step 1: Add SettingsStore**

Create `app/src/main/java/com/gouge/guaili/settings/SettingsStore.kt`:

```kotlin
package com.gouge.guaili.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "guaili_settings")

class SettingsStore(
    private val context: Context,
) {
    val settings: Flow<GuailiSettings> = context.dataStore.data.map { prefs ->
        val defaults = GuailiSettings.defaults()
        GuailiSettings(
            baseUrl = prefs[Keys.baseUrl] ?: defaults.baseUrl,
            symbols = parseCsv(prefs[Keys.symbols] ?: defaults.symbols.joinToString(",")),
            intervals = parseCsv(prefs[Keys.intervals] ?: defaults.intervals.joinToString(",")),
            autoRefreshSeconds = prefs[Keys.autoRefreshSeconds] ?: defaults.autoRefreshSeconds,
            limit = defaults.limit,
            calcLimit = prefs[Keys.calcLimit] ?: defaults.calcLimit,
            closedOnly = prefs[Keys.closedOnly] ?: defaults.closedOnly,
            maLength = prefs[Keys.maLength] ?: defaults.maLength,
            maType = prefs[Keys.maType] ?: defaults.maType,
            atrLen = prefs[Keys.atrLen] ?: defaults.atrLen,
            atrPercentLen = prefs[Keys.atrPercentLen] ?: defaults.atrPercentLen,
            maxAtrRank = prefs[Keys.maxAtrRank] ?: defaults.maxAtrRank,
            slopeMul = prefs[Keys.slopeMul] ?: defaults.slopeMul,
            useSlope = prefs[Keys.useSlope] ?: defaults.useSlope,
        )
    }

    suspend fun save(settings: GuailiSettings) {
        context.dataStore.edit { prefs ->
            prefs[Keys.baseUrl] = settings.baseUrl
            prefs[Keys.symbols] = settings.symbols.joinToString(",")
            prefs[Keys.intervals] = settings.intervals.joinToString(",")
            prefs[Keys.autoRefreshSeconds] = settings.autoRefreshSeconds
            prefs[Keys.calcLimit] = settings.calcLimit
            prefs[Keys.closedOnly] = settings.closedOnly
            prefs[Keys.maLength] = settings.maLength
            prefs[Keys.maType] = settings.maType
            prefs[Keys.atrLen] = settings.atrLen
            prefs[Keys.atrPercentLen] = settings.atrPercentLen
            prefs[Keys.maxAtrRank] = settings.maxAtrRank
            prefs[Keys.slopeMul] = settings.slopeMul
            prefs[Keys.useSlope] = settings.useSlope
        }
    }

    private object Keys {
        val baseUrl = stringPreferencesKey("base_url")
        val symbols = stringPreferencesKey("symbols")
        val intervals = stringPreferencesKey("intervals")
        val autoRefreshSeconds = intPreferencesKey("auto_refresh_seconds")
        val calcLimit = intPreferencesKey("calc_limit")
        val closedOnly = booleanPreferencesKey("closed_only")
        val maLength = intPreferencesKey("ma_length")
        val maType = stringPreferencesKey("ma_type")
        val atrLen = intPreferencesKey("atr_len")
        val atrPercentLen = intPreferencesKey("atr_percent_len")
        val maxAtrRank = doublePreferencesKey("max_atr_rank")
        val slopeMul = doublePreferencesKey("slope_mul")
        val useSlope = booleanPreferencesKey("use_slope")
    }
}
```

- [ ] **Step 2: Run compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: PASS.

## Task 7: Add ViewModel Refresh State

**Files:**
- Create: `app/src/main/java/com/gouge/guaili/ui/GuailiViewModel.kt`

- [ ] **Step 1: Add ViewModel state and refresh behavior**

Create `app/src/main/java/com/gouge/guaili/ui/GuailiViewModel.kt`:

```kotlin
package com.gouge.guaili.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gouge.guaili.data.GuailiRepository
import com.gouge.guaili.data.GuailiResult
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.toTable
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class GuailiTableState(
    val settings: GuailiSettings = GuailiSettings.defaults(),
    val symbols: List<String> = GuailiSettings.defaults().symbols,
    val intervals: List<String> = GuailiSettings.defaults().intervals,
    val cells: Map<String, Map<String, GuailiCell>> = emptyMap(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val lastUpdatedAt: Long? = null,
    val errorMessage: String? = null,
    val isStale: Boolean = false,
)

class GuailiViewModel(
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(GuailiTableState())
    val state: StateFlow<GuailiTableState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var repository: GuailiRepository? = null

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                repository = GuailiRepository.create(settings.baseUrl)
                _state.value = _state.value.copy(
                    settings = settings,
                    symbols = settings.symbols,
                    intervals = settings.intervals,
                )
                refresh()
                restartAutoRefresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val repo = repository ?: GuailiRepository.create(settings.baseUrl).also { repository = it }
            val hadData = _state.value.cells.isNotEmpty()
            _state.value = _state.value.copy(
                isLoading = !hadData,
                isRefreshing = hadData,
                errorMessage = null,
            )

            when (val result = repo.fetch(settings)) {
                is GuailiResult.Success -> {
                    val table = result.value.toTable(settings.symbols, settings.intervals)
                    _state.value = _state.value.copy(
                        settings = settings,
                        symbols = table.symbols,
                        intervals = table.intervals,
                        cells = table.cells,
                        isLoading = false,
                        isRefreshing = false,
                        lastUpdatedAt = System.currentTimeMillis(),
                        errorMessage = null,
                        isStale = false,
                    )
                }
                is GuailiResult.Failure -> {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = result.message,
                        isStale = hadData,
                    )
                }
            }
        }
    }

    fun saveSettings(settings: GuailiSettings) {
        viewModelScope.launch {
            settingsStore.save(settings)
        }
    }

    private fun restartAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                val seconds = _state.value.settings.autoRefreshSeconds.coerceAtLeast(1)
                delay(seconds * 1000L)
                refresh()
            }
        }
    }
}
```

- [ ] **Step 2: Run compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: PASS.

## Task 8: Build Dense Table UI

**Files:**
- Create: `app/src/main/java/com/gouge/guaili/ui/GuailiTable.kt`
- Create: `app/src/main/java/com/gouge/guaili/ui/CellDetailSheet.kt`

- [ ] **Step 1: Add cell detail sheet**

Create `app/src/main/java/com/gouge/guaili/ui/CellDetailSheet.kt`:

```kotlin
package com.gouge.guaili.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gouge.guaili.domain.GuailiCell

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CellDetailSheet(
    cell: GuailiCell,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text("${cell.symbol}  ${cell.interval}")
            DetailLine("value", cell.value?.toString())
            DetailLine("guaili", cell.guaili?.toString())
            DetailLine("ma", cell.ma?.toString())
            DetailLine("atr14", cell.atr14?.toString())
            DetailLine("atrRank", cell.atrRank?.toString())
            DetailLine("rankFilter", cell.rankFilter?.toString())
            DetailLine("longTrend", cell.longTrend?.toString())
            DetailLine("shortTrend", cell.shortTrend?.toString())
            DetailLine("isClosed", cell.isClosed?.toString())
            DetailLine("openTime", cell.openTime)
            DetailLine("closeTime", cell.closeTime)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    Text("$label: ${value ?: "-"}")
}
```

- [ ] **Step 2: Add dense table**

Create `app/src/main/java/com/gouge/guaili/ui/GuailiTable.kt`:

```kotlin
package com.gouge.guaili.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.guailiColorStrength

private val SymbolWidth = 84.dp
private val CellWidth = 56.dp
private val CellHeight = 38.dp

@Composable
fun GuailiTable(
    state: GuailiTableState,
    onCellClick: (GuailiCell) -> Unit,
) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()

    Column {
        Row {
            HeaderCell("Symbol", SymbolWidth)
            Row(modifier = Modifier.horizontalScroll(horizontal)) {
                state.intervals.forEach { interval ->
                    HeaderCell(formatInterval(interval), CellWidth)
                }
            }
        }

        Row(modifier = Modifier.verticalScroll(vertical)) {
            Column {
                state.symbols.forEach { symbol ->
                    HeaderCell(symbol, SymbolWidth)
                }
            }
            Column(modifier = Modifier.horizontalScroll(horizontal)) {
                state.symbols.forEach { symbol ->
                    Row {
                        state.intervals.forEach { interval ->
                            val cell = state.cells[symbol]?.get(interval)
                            ValueCell(cell = cell, onCellClick = onCellClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(CellHeight)
            .background(Color(0xFF202832))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFFE5E7EB),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ValueCell(
    cell: GuailiCell?,
    onCellClick: (GuailiCell) -> Unit,
) {
    val text = cell?.value?.toString() ?: "-"
    val background = cellBackground(cell)
    Box(
        modifier = Modifier
            .width(CellWidth)
            .height(CellHeight)
            .defaultMinSize(CellWidth, CellHeight)
            .background(background, RoundedCornerShape(2.dp))
            .then(if (cell != null) Modifier.clickable { onCellClick(cell) } else Modifier)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        if (cell?.isClosed == false) {
            Spacer(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .width(5.dp)
                    .height(5.dp)
                    .background(Color(0xFFFACC15), RoundedCornerShape(50)),
            )
        }
    }
}

private fun cellBackground(cell: GuailiCell?): Color {
    val strength = guailiColorStrength(cell?.value)
    return when {
        cell == null -> Color(0xFF151A1F)
        strength < 0 -> blend(Color(0xFF31363D), Color(0xFFBE0041), -strength)
        strength > 0 -> blend(Color(0xFF31363D), Color(0xFF009C22), strength)
        else -> Color(0xFF31363D)
    }
}

private fun blend(from: Color, to: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f,
    )
}

private fun formatInterval(interval: String): String =
    when (interval) {
        "60" -> "1h"
        "120" -> "2h"
        "180" -> "3h"
        "240" -> "4h"
        "360" -> "6h"
        "480" -> "8h"
        "720" -> "12h"
        else -> if (interval.all { it.isDigit() }) "${interval}m" else interval
    }
```

- [ ] **Step 3: Run compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: PASS.

## Task 9: Build Main Screen and Settings Sheet

**Files:**
- Create: `app/src/main/java/com/gouge/guaili/ui/GuailiScreen.kt`
- Create: `app/src/main/java/com/gouge/guaili/ui/SettingsSheet.kt`
- Modify: `app/src/main/java/com/gouge/guaili/MainActivity.kt`

- [ ] **Step 1: Add settings sheet**

Create `app/src/main/java/com/gouge/guaili/ui/SettingsSheet.kt`:

```kotlin
package com.gouge.guaili.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.parseCsv

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: GuailiSettings,
    onSave: (GuailiSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var symbols by remember { mutableStateOf(settings.symbols.joinToString(",")) }
    var intervals by remember { mutableStateOf(settings.intervals.joinToString(",")) }
    var refreshSeconds by remember { mutableStateOf(settings.autoRefreshSeconds.toString()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Backend URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(symbols, { symbols = it }, label = { Text("Symbols") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(intervals, { intervals = it }, label = { Text("Intervals") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                refreshSeconds,
                { refreshSeconds = it.filter(Char::isDigit) },
                label = { Text("Refresh seconds") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.padding(top = 12.dp)) {
                Button(
                    onClick = {
                        onSave(
                            settings.copy(
                                baseUrl = baseUrl,
                                symbols = parseCsv(symbols),
                                intervals = parseCsv(intervals),
                                autoRefreshSeconds = refreshSeconds.toIntOrNull()?.coerceAtLeast(1)
                                    ?: settings.autoRefreshSeconds,
                            ),
                        )
                        onDismiss()
                    },
                ) {
                    Text("Save")
                }
            }
        }
    }
}
```

- [ ] **Step 2: Add main screen**

Create `app/src/main/java/com/gouge/guaili/ui/GuailiScreen.kt`:

```kotlin
package com.gouge.guaili.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gouge.guaili.domain.GuailiCell

@Composable
fun GuailiScreen(viewModel: GuailiViewModel) {
    val state by viewModel.state.collectAsState()
    var selectedCell by remember { mutableStateOf<GuailiCell?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
    ) {
        Row {
            Button(onClick = viewModel::refresh) {
                Text(if (state.isRefreshing) "Refreshing" else "Refresh")
            }
            Button(onClick = { settingsOpen = true }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Settings")
            }
            Text(
                text = statusText(state),
                modifier = Modifier.padding(start = 12.dp, top = 10.dp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        state.errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        } else {
            GuailiTable(state = state, onCellClick = { selectedCell = it })
        }
    }

    selectedCell?.let { cell ->
        CellDetailSheet(cell = cell, onDismiss = { selectedCell = null })
    }

    if (settingsOpen) {
        SettingsSheet(
            settings = state.settings,
            onSave = viewModel::saveSettings,
            onDismiss = { settingsOpen = false },
        )
    }
}

private fun statusText(state: GuailiTableState): String {
    val updated = state.lastUpdatedAt?.let { "updated $it" } ?: "not updated"
    val stale = if (state.isStale) " stale" else ""
    return "$updated$stale"
}
```

- [ ] **Step 3: Wire MainActivity**

Replace `app/src/main/java/com/gouge/guaili/MainActivity.kt` with:

```kotlin
package com.gouge.guaili

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.GuailiScreen
import com.gouge.guaili.ui.GuailiViewModel
import com.gouge.guaili.ui.theme.GuailiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsStore = SettingsStore(applicationContext)
        val viewModel = ViewModelProvider(
            this,
            GuailiViewModelFactory(settingsStore),
        )[GuailiViewModel::class.java]

        setContent {
            GuailiTheme {
                GuailiScreen(viewModel)
            }
        }
    }
}

private class GuailiViewModelFactory(
    private val settingsStore: SettingsStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return GuailiViewModel(settingsStore) as T
    }
}
```

- [ ] **Step 4: Run compile check**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: PASS.

## Task 10: Final Verification

**Files:**
- Modify only files with compile or test failures found during verification.

- [ ] **Step 1: Run unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: all tests PASS.

- [ ] **Step 2: Build debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and APK generated under `app/build/outputs/apk/debug/`.

- [ ] **Step 3: Manual Android check**

Install and open the app on an emulator or device. Configure the backend base URL to the reachable HTTP service address, then verify:

- The default 10-symbol and 23-interval matrix renders.
- The table scrolls horizontally and vertically.
- Manual refresh triggers one backend request with comma-separated `symbols` and `intervals`.
- Auto-refresh updates the table without clearing old values first.
- Positive values are green, negative values are red, and missing values show `-`.
- Tapping a populated cell opens the detail sheet.
- Turning off the backend shows an error while keeping previous values visible.

## Self-Review

- Spec coverage: The plan covers Android project creation, batched guaili API access, settings defaults, 10 x 20+ dense matrix display, cell colors, unfinished-candle marker, detail sheet, polling refresh, error state, and tests.
- Placeholder scan: No `TODO`, `TBD`, or unspecified implementation steps remain.
- Type consistency: DTOs, domain models, mapper signatures, repository, ViewModel state, and UI references use the same names throughout the plan.

