package com.gouge.guaili.widget

import android.content.Context
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.util.SizeF
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.WorkManager
import androidx.glance.layout.*
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.gouge.guaili.data.*
import com.gouge.guaili.pullback.*
import com.gouge.guaili.settings.*
import com.gouge.guaili.ui.resolveAppLaunch
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PullbackWidgetIntegrationTest {
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun productionLazyListRendersTenSymbolsIncludingPreviouslyReservedIds() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val symbols = listOf("ADAUSDT", "SUIUSDT", "BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT",
            "XRPUSDT", "DOGEUSDT", "LINKUSDT", "XAUUSDT")
        val seed = fixture(context)
        val source = seed.copy(response = seed.response.copy(results = symbols.map {
            seed.response.results.first().copy(symbol = it)
        }))
        val config = WidgetConfig(symbols, emptyList(), WidgetMode.PullbackObservation)
        val settings = GuailiSettings.defaults().copy(baseUrl = source.baseUrl, symbols = symbols)
        val preferences = MarketSignalPreferences()
        val projection = PullbackWidgetProjector().project(source, preferences.pullback.rules)
        assertEquals(10, projection.rows.size)
        val frame = PullbackWidgetFrame(config, settings, preferences, source, null, projection,
            PullbackWidgetAlertSnapshot(), emptyMap())
        for (width in listOf(180, 320)) {
            // Exercise the production LazyColumn, not a Column-based visual preview.
            val result = GlanceRemoteViews().compose(context, DpSize(width.dp, 440.dp)) {
                PullbackObservationWidgetContent(frame, config, settings, WidgetRefreshStatus(), 1)
            }
            assertNotNull(result.remoteViews)
        }
    }

    private fun priceFixture(now: Long) = PriceAlertDto(42,1,1,"BTCUSDT","30","BTCUSDT","示例警报",
        PriceAlertGeometry("horizontal_segment",PriceAlertPoint(now-60_000,64250.0),PriceAlertPoint(now+86_400_000,64250.0)),
        "cross_up","once","active",now+19_200_000,"","","",createdAt=now,updatedAt=now,intent="open_long",dataStatus="live")
    private fun fixture(context: Context): ServerSignalsSnapshot {
        val device = readGuailiDeviceTime(context)
        val now = device.wallMillis
        fun evidence(periods: List<String>, values: List<Double>) = periods.zip(values).map { (period, value) ->
            ServerIntervalEvidence(period, "ready", value = (value * 10).toInt(), guaili = value, marketEventTime = now, isClosed = false)
        }
        val rows = listOf(
            ServerSymbolSignals("BTCUSDT", "ready", now, perIntervalQuality =
                evidence(listOf("10D","W","4D","3D","2D"), listOf(1.8,1.4,.6,.3,0.0)) +
                evidence(listOf("D","720","480","360","240"), listOf(-1.8,-1.4,-.6,-.3,-.6))),
            ServerSymbolSignals("ETHUSDT", "ready", now, perIntervalQuality =
                evidence(listOf("3D","2D","D","720","480"), listOf(-1.8,-1.4,-.6,-.3,.2))),
            ServerSymbolSignals("XAUUSDT", "ready", now, perIntervalQuality =
                evidence(listOf("W","4D","3D","2D","D"), listOf(1.8,1.4,.6,.3,.6))))
        return ServerSignalsSnapshot(ServerSignalsResponse(true,"ready",now,now,
            qualityConfig = ServerSignalQualityConfig(30_000,15_000), results = rows), now, "http://widget-test.invalid",
            GuailiServerClock(now,device.elapsedMillis,device.bootCount,10), true)
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun nativeRowsAndSingleHeaderKeepTheirFullTextAtNarrowWideLargeFontAndBothThemes() = runBlocking<Unit> {
        val target = ApplicationProvider.getApplicationContext<Context>()
        val source = fixture(target)
        val rules = PullbackRules()
        val projection = PullbackWidgetProjector().project(source,rules)
        assertTrue(projection.rows.first().long!!.highlighted)
        val config = WidgetConfig(projection.rows.map { it.symbol },emptyList(),WidgetMode.PullbackObservation)
        for (night in listOf(false,true)) for (scale in listOf(1f,1.5f)) for (width in listOf(180,250,320)) {
            val context = target.createConfigurationContext(Configuration(target.resources.configuration).apply {
                fontScale = scale
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            val dims = pullbackWidgetDimensions(context,width.toFloat(),projection.rows)
            val state = pullbackWidgetState(source,null,projection,config,source.baseUrl,readGuailiDeviceTime(target))
            val remote = GlanceRemoteViews().compose(context,DpSize(width.dp,600.dp)) {
                Column(GlanceModifier.fillMaxWidth().padding(horizontal = dims.outer.dp,vertical = 4.dp)) {
                    PullbackWidgetHeader(state,5,false,WidgetRefreshStatus(),source.updatedAt,source.response.evaluatedAt,
                        1,dims.wide,scale)
                    projection.rows.forEach { row ->
                      Column(GlanceModifier.fillMaxWidth()) {
                        androidx.glance.text.Text(row.symbol,modifier = GlanceModifier.padding(horizontal=dims.inner.dp,vertical = 3.dp),
                            style = androidx.glance.text.TextStyle(fontSize=12.sp,color=androidx.glance.color.ColorProvider(
                                Color(0xff57626d),Color(0xffb4bdc8))))
                        PullbackDirection.entries.forEach { direction ->
                            PullbackWidgetTarget(row.symbol,direction,row.candidate(direction),null,
                                priceFixture(source.response.serverTime).takeIf { row.symbol == "BTCUSDT" && direction == PullbackDirection.Long },false,
                                source.response.serverTime,dims,1,source.baseUrl)
                        }
                      }
                      Spacer(GlanceModifier.height(6.dp))
                    }
                }
            }.remoteViews
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val parent = FrameLayout(context)
                parent.setBackgroundColor(if (night) 0xff171d24.toInt() else 0xfff7f9fb.toInt())
                parent.addView(remote.apply(context,parent))
                val pixels = (width * context.resources.displayMetrics.density).toInt()
                parent.measure(View.MeasureSpec.makeMeasureSpec(pixels,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                parent.layout(0,0,pixels,parent.measuredHeight)
                val fields = mutableListOf<TextView>()
                val alertTargets = mutableListOf<View>()
                fun visit(view: View) {
                    if (view is TextView && view.visibility == View.VISIBLE && view.text.isNotEmpty()) fields += view
                    if (view.contentDescription?.contains("打开警报K线") == true) alertTargets += view
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(parent)
                if (width >= 250) {
                    assertTrue("Price alert must have an independent touch target", alertTargets.isNotEmpty())
                    assertTrue("Price alert touch height must be at least 48dp", alertTargets.all {
                        it.height >= 48 * context.resources.displayMetrics.density - 1
                    })
                    val price = fields.single { it.text.toString() == "@64250" }
                    assertTrue("Full price text must fit its view", price.height >=
                        price.layout.height + price.compoundPaddingTop + price.compoundPaddingBottom)
                    var child: View = price
                    while (child !== parent) {
                        val container = child.parent as View
                        assertTrue("$width / $scale / $night: price must not be clipped by its container",
                            child.top >= 0 && child.bottom <= container.height)
                        child = container
                    }
                }
                assertEquals(1,fields.count { it.text.toString() == "候选" })
                assertEquals(1,fields.count { it.text.toString() == "刷新" })
                assertFalse(fields.any { it.text.toString().startsWith("采样 ") })
                for (label in listOf("2D","2H","4H","10m","8H","20m","D","1H")) {
                    assertTrue("Missing $label; rendered ${fields.map { it.text }}",fields.any { it.text.toString() == label })
                    val field = fields.first { it.text.toString() == label }
                    assertNotNull(field.layout)
                    assertEquals("$width / $scale / $night: $label must not wrap",1,field.layout.lineCount)
                    assertEquals("$width / $scale / $night: $label must not truncate",0,field.layout.getEllipsisCount(0))
                }
                val header = fields.first { it.text.toString().startsWith("采样") || it.text.toString().matches(Regex("\\d{2}:\\d{2}.*")) || it.text.toString() == "快照旧" }
                assertTrue(header.layout.lineCount == 1 && header.layout.getEllipsisCount(0) == 0)
                val bitmap = Bitmap.createBitmap(pixels,parent.measuredHeight,Bitmap.Config.ARGB_8888)
                parent.draw(Canvas(bitmap))
                File(target.getExternalFilesDir(null),"pullback-widget-$width-$scale-${if (night) "dark" else "light"}.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
    }

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test fun phoneAlertsStayInTheRightColumnAcrossResponsiveBucketsAndLargeFonts() = runBlocking<Unit> {
        val target = ApplicationProvider.getApplicationContext<Context>()
        val source = fixture(target)
        val long = PullbackWidgetProjector().project(source, PullbackRules()).rows.first().long!!
        val short = long.copy(direction = PullbackDirection.Short, highlighted = false,
            evidence = long.evidence.dropLast(1) + long.target.copy(interval = "60"), entryInterval = "3")
        val rows = listOf(PullbackRow("BTCUSDT", long, short))
        val alerts = listOf(priceFixture(source.response.serverTime).let {
            it.copy(geometry = it.geometry.copy(first = it.geometry.first.copy(price = 81056.5)))
        }, priceFixture(source.response.serverTime).let {
            it.copy(id = 43, interval = "5", intent = "open_short",
                geometry = it.geometry.copy(first = it.geometry.first.copy(price = 82998.2)))
        })
        for (night in listOf(false, true)) for (scale in listOf(1f, 1.3f, 1.5f)) for (bucket in listOf(250, 320)) {
            val context = target.createConfigurationContext(Configuration(target.resources.configuration).apply {
                fontScale = scale
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            // A real 320dp host can select Glance's 250dp bucket after launcher padding.
            // Test the bucket separately from the width that RemoteViews actually receives.
            val dims = pullbackWidgetDimensions(context, bucket.toFloat(), rows)
            val remote = GlanceRemoteViews().compose(context, DpSize(bucket.dp, 300.dp)) {
                Column(GlanceModifier.fillMaxWidth().padding(horizontal = dims.outer.dp, vertical = 4.dp)) {
                    androidx.glance.text.Text("BTCUSDT", style = androidx.glance.text.TextStyle(fontSize = 12.sp,
                        color = androidx.glance.color.ColorProvider(Color(0xff57626d), Color(0xffb4bdc8))))
                    PullbackWidgetTarget("BTCUSDT", PullbackDirection.Long, long, null, alerts[0], true,
                        source.response.serverTime, dims, 1, source.baseUrl)
                    PullbackWidgetTarget("BTCUSDT", PullbackDirection.Short, short, null, alerts[1], true,
                        source.response.serverTime, dims, 1, source.baseUrl)
                }
            }.remoteViews
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val parent = FrameLayout(context)
                parent.setBackgroundColor(if (night) 0xff171d24.toInt() else 0xfff7f9fb.toInt())
                parent.addView(remote.apply(context, parent))
                val pixels = (304 * context.resources.displayMetrics.density).toInt()
                parent.measure(View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                parent.layout(0, 0, pixels, parent.measuredHeight)
                val fields = mutableListOf<TextView>()
                fun visit(view: View) {
                    if (view is TextView && view.visibility == View.VISIBLE) fields += view
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(parent)
                val bounds = fields.filter { it.text.toString() in listOf("2D", "1H", "做多", "做空", "@81056.5", "@82998.2") }
                    .associate { field -> field.text.toString() to android.graphics.Rect().also {
                        field.getDrawingRect(it); parent.offsetDescendantRectToMyCoords(field, it)
                    } }
                for ((level, intent, price) in listOf(Triple("2D", "做多", "@81056.5"), Triple("1H", "做空", "@82998.2"))) {
                    assertTrue("Alert must remain to the right of $level", bounds.getValue(intent).left > bounds.getValue(level).right)
                    assertTrue("Alert must overlap the target row vertically", bounds.getValue(intent).top < bounds.getValue(level).bottom)
                    assertTrue("Price must remain beside the target row", bounds.getValue(price).top < bounds.getValue(level).bottom)
                }
                for (field in fields.filter { it.text.startsWith("@") || it.text == "待更新" }) {
                    assertEquals("Alert must not truncate", 0, field.layout.getEllipsisCount(0))
                    var child: View = field
                    while (child !== parent) {
                        val container = child.parent as View
                        assertTrue("Alert must stay within its container", child.left >= 0 && child.right <= container.width &&
                            child.top >= 0 && child.bottom <= container.height)
                        child = container
                    }
                }
                val bitmap = Bitmap.createBitmap(pixels, parent.measuredHeight, Bitmap.Config.ARGB_8888)
                parent.draw(Canvas(bitmap))
                File(target.getExternalFilesDir(null), "pullback-fixed-phone-$bucket-$scale-${if (night) "dark" else "light"}.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Test fun frozenEvidenceOpensOriginalPeriodAndAlertNavigationRetainsIdentity() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = fixture(context)
        val candidate = PullbackWidgetProjector().project(source,PullbackRules()).rows.first().long!!
        val payload = freezePullbackWidgetEvidence("BTCUSDT",candidate,PullbackRules(),source)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<PullbackWidgetActivity>(pullbackWidgetActivityIntent(context,-1,
            PullbackWidgetJson.encodeToString(payload))).use {
            assertTrue(device.wait(Until.hasObject(By.text("BTCUSDT · 多头依据")),8_000))
            assertTrue(device.hasObject(By.text("2D")))
            assertTrue(device.hasObject(By.text("查看 2D K 线")))
            device.takeScreenshot(File(context.getExternalFilesDir(null),"pullback-widget-evidence-native.png"))
            device.pressBack()
            assertFalse(device.wait(Until.hasObject(By.text("BTCUSDT · 多头依据")),500))
        }
        val entry = resolveAppLaunch(pullbackWidgetKlineIntent(context,"BTCUSDT","120",source.baseUrl))!!
        assertEquals("120",entry.klineTarget!!.interval)
        assertTrue(entry.klineTarget!!.fromPullback)
        val alert = resolveAppLaunch(pullbackWidgetKlineIntent(context,"ETHUSDT","30",source.baseUrl,42))!!
        assertEquals(42L,alert.klineTarget!!.priceAlertId)
        assertEquals("30",alert.klineTarget!!.interval)
    }

    @Test fun hostedWidgetRefreshFilterFrozenEvidenceAndResizeUseRealRemoteViews() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("pullback_widget_host") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val settingsStore = SettingsStore(context)
        val originalSettings = settingsStore.settings.first()
        val snapshots = ServerSignalsSnapshotStore(context)
        val originalSnapshot = snapshots.read()
        val originalFailure = snapshots.readFailure()
        val configurations = WidgetConfigStore(context)
        val host = AppWidgetHost(context,27188)
        val manager = AppWidgetManager.getInstance(context)
        val id = host.allocateAppWidgetId()
        val server = MockWebServer()
        val signalReads = java.util.concurrent.atomic.AtomicInteger()
        val alertReads = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when {
                    request.path?.startsWith("/api/signals") == true -> {
                        signalReads.incrementAndGet(); PullbackWidgetJson.encodeToString(fixture(context).response)
                    }
                    request.path?.startsWith("/api/price-alerts") == true -> {
                        alertReads.incrementAndGet(); PullbackWidgetJson.encodeToString(listOf(priceFixture(System.currentTimeMillis())))
                    }
                    else -> "[]"
                }
                return MockResponse().setHeader("Content-Type","application/json").setBody(body)
            }
        }
        server.start()
        val baseUrl = server.url("/").toString()
        fun stopWork() {
            listOf("guaili-widget-periodic-refresh","guaili-widget-immediate-refresh","guaili-widget-immediate-refresh-server")
                .forEach { WorkManager.getInstance(context).cancelUniqueWork(it).result.get() }
        }
        try {
            settingsStore.save(originalSettings.copy(baseUrl = baseUrl,symbols = listOf("BTCUSDT","ETHUSDT","XAUUSDT")))
            snapshots.save(fixture(context).copy(baseUrl = baseUrl))
            configurations.save(id,WidgetConfig(listOf("BTCUSDT","ETHUSDT","XAUUSDT"),emptyList(),WidgetMode.PullbackObservation))
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,180)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,440)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,440)
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,arrayListOf(SizeF(180f,440f)))
            }
            assertTrue("Grant the test emulator's appwidget bind permission first",manager.bindAppWidgetIdIfAllowed(id,
                ComponentName(context,GuailiWidgetReceiver::class.java),options))
            ActivityScenario.launch<PullbackWidgetActivity>(pullbackWidgetActivityIntent(context,id)).use { scenario ->
                var hostView: android.appwidget.AppWidgetHostView? = null
                scenario.onActivity { activity ->
                    host.startListening()
                    hostView = host.createView(activity,id,manager.getAppWidgetInfo(id))
                    val container = FrameLayout(activity)
                    container.setPadding(16,80,0,0)
                    container.addView(hostView,FrameLayout.LayoutParams((180*activity.resources.displayMetrics.density).toInt(),
                        (440*activity.resources.displayMetrics.density).toInt()))
                    activity.setContentView(container)
                }
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(id)
                GuailiWidget().update(context,glanceId)
                assertTrue(device.wait(Until.hasObject(By.text("2D")),12_000))
                assertTrue(device.hasObject(By.text("候选")))
                assertFalse(device.hasObject(By.textStartsWith("采样 ")))
                fun capture(name: String) {
                    device.waitForIdle()
                    val bounds = android.graphics.Rect()
                    scenario.onActivity {
                        val view = requireNotNull(hostView)
                        view.getGlobalVisibleRect(bounds)
                        var header: TextView? = null
                        fun findHeader(node: View) {
                            if (node is TextView && node.text.toString().matches(Regex("(采样)?\\d{2}:\\d{2}.*"))) header = node
                            if (node is ViewGroup) for (i in 0 until node.childCount) findHeader(node.getChildAt(i))
                        }
                        findHeader(view)
                        assertNotNull("The real Host must show its sample time", header)
                        assertEquals("The real Host must show the entire sample time", 0, header!!.layout.getEllipsisCount(0))
                    }
                    val screen = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    val bitmap = Bitmap.createBitmap(screen,bounds.left,bounds.top,bounds.width(),bounds.height())
                    File(context.getExternalFilesDir(null),name).outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG,100,stream) }
                }
                capture("pullback-widget-host-narrow.png")
                stopWork()
                val previousSignalReads = signalReads.get()
                val previousAlertReads = alertReads.get()
                val previousSnapshot = snapshots.read()!!.updatedAt
                device.findObject(By.text("刷新")).click()
                withTimeout(15_000) {
                    while (signalReads.get() <= previousSignalReads || alertReads.get() <= previousAlertReads ||
                        snapshots.read()?.updatedAt == previousSnapshot ||
                        PullbackWidgetAlertStore(context, baseUrl).snapshots.first().receipt == null) delay(100)
                }
                assertFalse(pullbackWidgetAlertsStale(baseUrl,
                    PullbackWidgetAlertStore(context, baseUrl).snapshots.first(), readGuailiDeviceTime(context)))
                assertTrue(device.wait(Until.hasObject(By.text("刷新")), 8_000))
                device.findObject(By.text("候选")).click()
                withTimeout(8_000) { while (!configurations.read(id,settingsStore.settings.first()).pullbackOnlyCandidates) delay(100) }
                assertTrue(device.wait(Until.gone(By.text("4H")),8_000))
                device.findObject(By.text("2D")).click()
                assertTrue(device.wait(Until.hasObject(By.text("BTCUSDT · 多头依据")),8_000))
                stopWork()
                val next = fixture(context)
                snapshots.save(next.copy(baseUrl = baseUrl,response = next.response.copy(results = next.response.results.map { row ->
                    if (row.symbol != "BTCUSDT") row else row.copy(perIntervalQuality = row.perIntervalQuality.map {
                        if (it.interval != "2D") it else it.copy(guaili = .7,value = 7)
                    })
                })))
                GuailiWidget().update(context,glanceId)
                assertTrue(device.hasObject(By.textContains("强势 · 触及均线")))
                device.pressBack()
                assertTrue(device.wait(Until.gone(By.text("BTCUSDT · 多头依据")),3_000))
                device.findObject(By.text("候选")).click()
                withTimeout(8_000) { while (configurations.read(id,settingsStore.settings.first()).pullbackOnlyCandidates) delay(100) }
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,320)
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,320)
                options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,arrayListOf(SizeF(320f,440f)))
                scenario.onActivity { activity ->
                    hostView!!.layoutParams = hostView!!.layoutParams.apply { width = (320*activity.resources.displayMetrics.density).toInt() }
                    manager.updateAppWidgetOptions(id,options)
                }
                GuailiWidget().update(context,glanceId)
                assertTrue(device.wait(Until.hasObject(By.text("编辑")),8_000))
                capture("pullback-widget-host-wide.png")
                assertFalse(device.hasObject(By.text("BTCUSDT · 多头依据")))
            }
        } finally {
            stopWork()
            host.deleteAppWidgetId(id)
            host.stopListening()
            configurations.delete(id)
            settingsStore.save(originalSettings)
            if (originalSnapshot == null) snapshots.clear() else snapshots.save(originalSnapshot)
            snapshots.recordFailure(originalFailure)
            server.shutdown()
        }
    }

    @Test fun alertSnapshotsRejectReadsStartedBeforeConfirmationAndStayIsolatedByService() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = "http://widget-cache-test-${System.nanoTime()}"
        val store = PullbackWidgetAlertStore(context,url)
        val alert = priceFixture(System.currentTimeMillis())
        assertTrue(store.publish(PriceAlertResult.Success(listOf(alert)),0))
        assertFalse("Unchanged results must not rewrite the widget cache",store.publish(PriceAlertResult.Success(listOf(alert)),0))
        val confirmation = com.gouge.guaili.ui.PriceAlertConfirmation(PriceAlertOperation("patch","cache-test",alert.id,
            alert.revision,buildJsonObject {}),alert.copy(revision=2))
        confirmPullbackWidgetAlert(context,url,confirmation)
        store.confirm(confirmation)
        assertFalse(store.publish(PriceAlertResult.Success(listOf(alert)),0))
        assertEquals(2L,store.snapshots.first().alerts.single().revision)
        assertTrue(PullbackWidgetAlertStore(context,"$url-other").snapshots.first().alerts.isEmpty())
    }

    @Test fun unchangedAlertReadsPersistVerificationAcrossStoreReloadWithoutReencodingTheList() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = "http://receipt-test-${System.nanoTime()}"
        val store = PullbackWidgetAlertStore(context, url)
        val time = readGuailiDeviceTime(context)
        val alerts = listOf(priceFixture(time.wallMillis))
        assertTrue(store.publish(PriceAlertResult.Success(alerts), 0, time))
        val saved = store.snapshots.first()
        assertEquals(time, saved.receipt)
        val later = time.copy(elapsedMillis = time.elapsedMillis + 60_000, wallMillis = time.wallMillis + 60_000)
        assertFalse(store.publish(PriceAlertResult.Success(alerts), 0,
            time.copy(elapsedMillis = time.elapsedMillis + 10_000)))
        assertTrue(store.publish(PriceAlertResult.Success(alerts), 0, later))
        val restored = PullbackWidgetAlertStore(context, url).snapshots.first()
        assertSame("Receipt-only saves must reuse the decoded list", saved.alerts, restored.alerts)
        assertEquals(saved.updatedAt, restored.updatedAt)
        assertEquals(later, restored.receipt)
        assertFalse(pullbackWidgetAlertsStale(url, restored, later.copy(elapsedMillis = later.elapsedMillis + 45_000)))
        assertTrue(pullbackWidgetAlertsStale(url, restored, later.copy(bootCount = (later.bootCount ?: 0) + 1)))
        assertTrue(store.publish(PriceAlertResult.Failure("offline"), 0, later))
        assertTrue(pullbackWidgetAlertsStale(url, store.snapshots.first(), later))
        assertTrue(store.publish(PriceAlertResult.Success(alerts), 0, later))
        assertFalse(pullbackWidgetAlertsStale(url, store.snapshots.first(), later))
        assertFalse(store.publish(PriceAlertResult.Success(emptyList()), 1, later))
        assertEquals(alerts, store.snapshots.first().alerts)
    }

    @Test fun anotherServiceCannotPostponeAnAlreadyScheduledEarlierExpiryCheck() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val work = WorkManager.getInstance(context)
        val name = "pullback-widget-cache-expiry"
        work.cancelUniqueWork(name).result.get()
        clearPullbackExpirySchedule()
        try {
            val receipt = readGuailiDeviceTime(context)
            schedulePullbackWidgetExpiry(context, "http://current-source.invalid",
                receipt.copy(elapsedMillis = receipt.elapsedMillis - 1_000))
            suspend fun pending(): androidx.work.WorkInfo = withTimeout(5_000) {
                while (true) {
                    work.getWorkInfosForUniqueWork(name).get().firstOrNull {
                        it.state == androidx.work.WorkInfo.State.ENQUEUED
                    }?.let { return@withTimeout it }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            val earlier = pending().id
            schedulePullbackWidgetExpiry(context, "http://late-old-source.invalid", receipt)
            assertEquals("A delayed old-source read must not postpone the current-source clock check", earlier, pending().id)
        } finally {
            work.cancelUniqueWork(name).result.get()
            clearPullbackExpirySchedule()
        }
    }
}
