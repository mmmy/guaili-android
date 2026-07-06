package com.gouge.guaili

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
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
}
