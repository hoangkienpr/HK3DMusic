package com.hk.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DlnaManagerTest {

    private val sampleXml = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <friendlyName>Living Room TV</friendlyName>
            <modelName>Samsung TV</modelName>
            <UDN>uuid:abc-123</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/AVTransport/control</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/RenderingControl/control</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun parseDeviceDescription_extractsNameAndControlUrls() {
        val dlna = DlnaManager()
        val dev = dlna.parseDeviceDescription(sampleXml, "http://192.168.1.5:9197/desc.xml")
        assertNotNull(dev)
        assertEquals("Living Room TV", dev!!.name)
        assertEquals("uuid:abc-123", dev.id)
        assertEquals("http://192.168.1.5:9197/AVTransport/control", dev.controlUrl)
        assertEquals("http://192.168.1.5:9197/RenderingControl/control", dev.renderingControlUrl)
    }

    @Test
    fun parseDeviceDescription_returnsNullWhenNoAvTransport() {
        val xml = sampleXml.replace("AVTransport", "ConnectionManager")
        val dlna = DlnaManager()
        val dev = dlna.parseDeviceDescription(xml, "http://192.168.1.5:9197/desc.xml")
        assertNull(dev)
    }

    @Test
    fun parseTime_parsesHms() {
        val dlna = DlnaManager()
        assertEquals(3661, dlna.parseTime("01:01:01"))
        assertEquals(0, dlna.parseTime("bad"))
        assertEquals(125, dlna.parseTime("00:02:05"))
    }

    @Test
    fun tagValue_extractsTag() {
        val dlna = DlnaManager()
        assertEquals("PLAYING", dlna.tagValue("<a><CurrentTransportState>PLAYING</CurrentTransportState></a>", "CurrentTransportState"))
        assertEquals("", dlna.tagValue("<a></a>", "CurrentTransportState"))
    }

    @Test
    fun xmlEscape_escapesSpecialChars() {
        val dlna = DlnaManager()
        assertEquals("&lt;title&gt; &amp; &quot;x&quot;", dlna.xmlEscape("<title> & \"x\""))
    }
}
