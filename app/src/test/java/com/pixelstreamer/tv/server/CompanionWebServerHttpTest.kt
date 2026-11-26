package com.pixelstreamer.tv.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class CompanionWebServerHttpTest {

    @Test
    fun socketServer_enforcesPinAcrossApiAndSetsCookieOnAuthorizedRoot() {
        val server = CompanionWebServer(sessionPin = "4829") {
            """{"albums":[],"moviesCount":1,"showsCount":0,"photosCount":0}"""
        }
        server.start()
        try {
            val port = server.port
            assertTrue("Expected bound ephemeral port, got $port", port > 0)

            // 1. Unauthenticated /api/state -> 401
            val unauthApi = request("http://127.0.0.1:$port/api/state")
            assertEquals(401, unauthApi.code)
            assertTrue(unauthApi.body.contains("Pairing PIN required"))

            // 2. Wrong X-PS-Pin -> 401
            val wrongPinApi = request(
                "http://127.0.0.1:$port/api/state",
                headers = mapOf("X-PS-Pin" to "0000")
            )
            assertEquals(401, wrongPinApi.code)

            // 3. Correct X-PS-Pin -> 200 + state JSON
            val okPinApi = request(
                "http://127.0.0.1:$port/api/state",
                headers = mapOf("X-PS-Pin" to "4829")
            )
            assertEquals(200, okPinApi.code)
            assertTrue(okPinApi.body.contains("\"moviesCount\":1"))

            // 4. Unauthenticated GET / -> 200 PIN gate without Set-Cookie
            val unauthRoot = request("http://127.0.0.1:$port/")
            assertEquals(200, unauthRoot.code)
            assertTrue(unauthRoot.body.contains("PIN"))
            assertFalse(unauthRoot.setCookie.contains("ps_auth="))

            // 5. Authorized GET /?pin=4829 -> 200 Companion HTML + HttpOnly Set-Cookie
            val authRoot = request("http://127.0.0.1:$port/?pin=4829")
            assertEquals(200, authRoot.code)
            assertTrue(authRoot.setCookie.contains("ps_auth=4829"))

            // 6. Subsequent /api/state using Cookie only -> 200
            val cookieApi = request(
                "http://127.0.0.1:$port/api/state",
                headers = mapOf("Cookie" to "ps_auth=4829")
            )
            assertEquals(200, cookieApi.code)
            assertTrue(cookieApi.body.contains("\"moviesCount\":1"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun isPinMatch_rejectsBlankAndMismatchedPins() {
        assertFalse(CompanionWebServer.isPinMatch("", ""))
        assertFalse(CompanionWebServer.isPinMatch("4829", ""))
        assertFalse(CompanionWebServer.isPinMatch("4829", "4828"))
        assertTrue(CompanionWebServer.isPinMatch("4829", "4829"))
    }

    private data class HttpResponse(val code: Int, val body: String, val setCookie: String)

    private fun request(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            requestMethod = "GET"
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val setCookie = conn.getHeaderField("Set-Cookie").orEmpty()
        conn.disconnect()
        return HttpResponse(code, body, setCookie)
    }
}
