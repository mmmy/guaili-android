package com.gouge.guaili

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidManifestTest {
    @Test
    fun manifestAllowsCleartextTrafficForLocalBackendDevelopment() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first(File::exists)

        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)
        val application = document.getElementsByTagName("application").item(0)
        val usesCleartextTraffic = application.attributes
            .getNamedItem("android:usesCleartextTraffic")
            ?.nodeValue

        assertEquals("true", usesCleartextTraffic)
    }

    @Test
    fun manifestRegistersHomeScreenWidget() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first(File::exists)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)

        val receivers = document.getElementsByTagName("receiver")
        val widgetReceiver = (0 until receivers.length)
            .map(receivers::item)
            .firstOrNull { receiver ->
                receiver.attributes.getNamedItem("android:name")?.nodeValue ==
                    ".widget.GuailiWidgetReceiver"
            }

        assertNotNull(widgetReceiver)
        val metadata = document.getElementsByTagName("meta-data")
        val widgetMetadata = (0 until metadata.length)
            .map(metadata::item)
            .firstOrNull { node ->
                node.attributes.getNamedItem("android:name")?.nodeValue ==
                    "android.appwidget.provider"
            }
        assertEquals(
            "@xml/guaili_widget_info",
            widgetMetadata?.attributes?.getNamedItem("android:resource")?.nodeValue,
        )
    }
}
