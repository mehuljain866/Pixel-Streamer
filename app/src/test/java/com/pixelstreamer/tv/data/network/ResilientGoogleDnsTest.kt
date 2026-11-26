package com.pixelstreamer.tv.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address

class ResilientGoogleDnsTest {

    @Test
    fun parseDohJsonAnswer_keepsValidTypeARecords_andDropsSinkholesAndNonA() {
        val body = """
            {
              "Status": 0,
              "Answer": [
                {"name": "example.com.", "type": 5, "data": "alias.example.com."},
                {"name": "example.com.", "type": 1, "data": "49.44.79.101"},
                {"name": "example.com.", "type": 1, "data": "0.0.0.0"},
                {"name": "example.com.", "type": 1, "data": "127.0.0.1"},
                {"name": "example.com.", "type": 1, "data": "142.250.193.78"},
                {"name": "example.com.", "type": 1, "data": "999.1.1.1"},
                {"name": "example.com.", "type": 1, "data": "172.217.160.110"}
              ]
            }
        """.trimIndent()

        val resolved = ResilientGoogleDns.parseDohJsonAnswer(body)
        assertEquals(listOf("142.250.193.78", "172.217.160.110"), resolved.map { it.hostAddress })
        assertTrue(resolved.all { it is Inet4Address })
    }

    @Test
    fun parseDohJsonAnswer_emptyWhenAnswerMissing() {
        assertTrue(ResilientGoogleDns.parseDohJsonAnswer("""{"Status": 3}""").isEmpty())
    }

    @Test
    fun isSinkholeIp_detectsKnownSinkholes() {
        assertTrue(ResilientGoogleDns.isSinkholeIp("49.44.79.1"))
        assertTrue(ResilientGoogleDns.isSinkholeIp("0.0.0.0"))
        assertTrue(ResilientGoogleDns.isSinkholeIp("127.0.0.1"))
        assertFalse(ResilientGoogleDns.isSinkholeIp("8.8.8.8"))
    }

    @Test
    fun parseNumericIpv4_validatesOctets() {
        assertEquals("8.8.4.4", ResilientGoogleDns.parseNumericIpv4("8.8.4.4")?.hostAddress)
        assertEquals("0.0.0.0", ResilientGoogleDns.parseNumericIpv4("0.0.0.0")?.hostAddress)
        assertNull(ResilientGoogleDns.parseNumericIpv4("256.1.1.1"))
        assertNull(ResilientGoogleDns.parseNumericIpv4("-1.0.0.1"))
        assertNull(ResilientGoogleDns.parseNumericIpv4("1.2.3"))
        assertNull(ResilientGoogleDns.parseNumericIpv4("1.2.3.4.5"))
        assertNull(ResilientGoogleDns.parseNumericIpv4("abc.def.0.1"))
    }
}
