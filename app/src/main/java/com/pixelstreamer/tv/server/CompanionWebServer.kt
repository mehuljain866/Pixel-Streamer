package com.pixelstreamer.tv.server

import com.pixelstreamer.tv.data.model.AlbumType
import com.pixelstreamer.tv.data.model.VideoQuality
import com.pixelstreamer.tv.data.repo.MediaRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Lightweight embedded LAN HTTP server so users can paste Google Photos shared album links,
 * select/unselect items, rename movies, and configure TMDB keys directly from their phone browser.
 */
class CompanionWebServer private constructor(
    private val repo: MediaRepository?,
    val sessionPin: String,
    private val preferredPort: Int,
    private val stateJsonProvider: (() -> String)?
) {

    constructor(repo: MediaRepository, sessionPin: String = generatePin()) :
        this(repo, sessionPin, DEFAULT_PORT, null)

    internal constructor(sessionPin: String = generatePin(), stateJsonProvider: () -> String) :
        this(null, sessionPin, 0, stateJsonProvider)

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var scope: CoroutineScope? = null

    var port: Int = DEFAULT_PORT
        private set

    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }

    @Synchronized
    fun start(): String? {
        if (serverSocket != null) return getServerUrl()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
        scope = s
        val ss = try {
            ServerSocket(preferredPort)
        } catch (_: Exception) {
            runCatching { ServerSocket(0) }.getOrNull()
        } ?: return null

        serverSocket = ss
        port = ss.localPort
        acceptJob = s.launch {
            while (!ss.isClosed) {
                try {
                    val client = ss.accept()
                    s.launch {
                        try {
                            handleClient(client)
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Exception) {
                    if (ss.isClosed) break
                }
            }
        }
        return getServerUrl()
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        scope?.cancel()
        scope = null
    }

    fun getServerUrl(): String? {
        val ip = findLocalIpv4() ?: return null
        return "http://$ip:$port/?pin=$sessionPin"
    }

    private fun handleClient(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()

            val headerBytes = ByteArrayOutputStream()
            var prev = 0
            var matchState = 0
            while (true) {
                val b = input.read()
                if (b == -1) return
                headerBytes.write(b)
                if (headerBytes.size() > 32 * 1024) return
                when {
                    (matchState == 0 || matchState == 2) && b == '\r'.code -> matchState++
                    matchState == 1 && b == '\n'.code -> matchState++
                    matchState == 3 && b == '\n'.code -> break
                    else -> matchState = if (b == '\r'.code) 1 else 0
                }
                prev = b
            }

            val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(" ") ?: return
            if (requestLine.size < 2) return
            val method = requestLine[0].uppercase()
            val rawTarget = requestLine[1]
            val path = rawTarget.substringBefore("?")
            val query = rawTarget.substringAfter("?", "")

            var contentLength = 0
            var headerPin = ""
            var cookieHeader = ""
            for (line in lines.drop(1)) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                when {
                    key.equals("Content-Length", ignoreCase = true) -> {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                    key.equals("X-PS-Pin", ignoreCase = true) -> {
                        headerPin = value
                    }
                    key.equals("Cookie", ignoreCase = true) -> {
                        cookieHeader = value
                    }
                }
            }

            val bodyStr = if (contentLength in 1..(1024 * 1024)) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                String(buf, 0, read, StandardCharsets.UTF_8)
            } else {
                ""
            }

            val queryPin = extractQueryParam(query, "pin")
            val cookiePin = extractCookie(cookieHeader, AUTH_COOKIE_NAME)
            val authorized = isAuthorizedRequest(
                expectedPin = sessionPin,
                queryPin = queryPin,
                headerPin = headerPin,
                cookiePin = cookiePin
            )

            val (status, contentType, respBody, setCookie) = dispatch(method, path, bodyStr, authorized)
            val bodyBytes = respBody.toByteArray(StandardCharsets.UTF_8)
            val respHeaders = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: $contentType; charset=utf-8\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                if (setCookie && isPinMatch(sessionPin, queryPin)) {
                    append("Set-Cookie: $AUTH_COOKIE_NAME=$sessionPin; Path=/; HttpOnly; SameSite=Strict\r\n")
                }
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(respHeaders.toByteArray(StandardCharsets.UTF_8))
            out.write(bodyBytes)
            out.flush()
        }
    }

    private data class DispatchResult(
        val status: String,
        val contentType: String,
        val body: String,
        val setCookie: Boolean = false
    )

    private fun dispatch(
        method: String,
        path: String,
        body: String,
        authorized: Boolean
    ): DispatchResult {
        return try {
            if (method == "GET" && path == "/") {
                return if (authorized) {
                    DispatchResult("200 OK", "text/html", COMPANION_HTML, setCookie = true)
                } else {
                    DispatchResult("200 OK", "text/html", PIN_GATE_HTML)
                }
            }

            if (path.startsWith("/api/") && !authorized) {
                return DispatchResult(
                    "401 Unauthorized",
                    "application/json",
                    """{"ok":false,"error":"Pairing PIN required. Scan the TV QR code or enter the PIN shown on TV."}"""
                )
            }

            if (method == "GET" && path == "/api/state") {
                return DispatchResult("200 OK", "application/json", buildStateJson())
            }
            val activeRepo = repo ?: return DispatchResult(
                "503 Service Unavailable",
                "application/json",
                """{"ok":false,"error":"Repository unavailable"}"""
            )

            when {
                method == "POST" && path == "/api/albums" -> {
                    val json = JSONObject(body)
                    val url = json.optString("url")
                    val title = json.optString("customTitle")
                    val typeStr = json.optString("type", "AUTO")
                    val type = AlbumType.entries.firstOrNull { it.name == typeStr } ?: AlbumType.AUTO
                    val res = runBlocking { activeRepo.addAndSyncAlbum(url, title, type) }
                    if (res.isSuccess) {
                        DispatchResult("200 OK", "application/json", JSONObject().put("ok", true).put("album", res.getOrThrow().displayTitle).toString())
                    } else {
                        DispatchResult("400 Bad Request", "application/json", JSONObject().put("ok", false).put("error", res.exceptionOrNull()?.message ?: "Failed").toString())
                    }
                }
                method == "POST" && path == "/api/albums/toggle" -> {
                    val json = JSONObject(body)
                    runBlocking { activeRepo.toggleAlbumEnabled(json.getString("albumId"), json.getBoolean("enabled")) }
                    DispatchResult("200 OK", "application/json", """{"ok":true}""")
                }
                method == "POST" && path == "/api/albums/delete" -> {
                    val json = JSONObject(body)
                    runBlocking { activeRepo.removeAlbum(json.getString("albumId")) }
                    DispatchResult("200 OK", "application/json", """{"ok":true}""")
                }
                method == "POST" && path == "/api/items/toggle" -> {
                    val json = JSONObject(body)
                    runBlocking {
                        activeRepo.toggleItemHidden(
                            json.getString("albumId"),
                            json.getString("itemId"),
                            json.getBoolean("hidden")
                        )
                    }
                    DispatchResult("200 OK", "application/json", """{"ok":true}""")
                }
                method == "POST" && path == "/api/items/rename" -> {
                    val json = JSONObject(body)
                    val meta = runBlocking {
                        activeRepo.setCustomMovieTitleAndRematch(
                            json.getString("albumId"),
                            json.getString("itemId"),
                            json.optString("title")
                        )
                    }
                    DispatchResult(
                        "200 OK",
                        "application/json",
                        JSONObject().put("ok", true).put("matched", meta?.matchedTitle ?: "").toString()
                    )
                }
                method == "POST" && path == "/api/settings" -> {
                    val json = JSONObject(body)
                    if (json.has("tmdbApiKey")) {
                        activeRepo.tmdbApiKey = json.optString("tmdbApiKey")
                    }
                    if (json.has("quality")) {
                        val q = VideoQuality.entries.firstOrNull { it.name == json.optString("quality") }
                        if (q != null) activeRepo.defaultQuality = q
                    }
                    DispatchResult("200 OK", "application/json", """{"ok":true}""")
                }
                method == "POST" && path == "/api/sync" -> {
                    runBlocking { activeRepo.syncAllEnabledAlbums() }
                    DispatchResult("200 OK", "application/json", """{"ok":true}""")
                }
                else -> DispatchResult("404 Not Found", "application/json", """{"error":"Not found"}""")
            }
        } catch (e: Exception) {
            DispatchResult("500 Internal Server Error", "application/json", JSONObject().put("ok", false).put("error", e.message ?: "Error").toString())
        }
    }

    private fun buildStateJson(): String {
        stateJsonProvider?.invoke()?.let { return it }
        val activeRepo = repo!!
        val albumsArr = JSONArray()
        val itemsMap = activeRepo.itemsByAlbum.value
        for (a in activeRepo.albums.value) {
            val itemsArr = JSONArray()
            for (it in itemsMap[a.id].orEmpty()) {
                itemsArr.put(
                    JSONObject().apply {
                        put("id", it.id)
                        put("title", it.displayTitle)
                        put("seriesTitle", if (it.isEpisode) it.seriesDisplayTitle else "")
                        put("episodeCode", it.episodeCode)
                        put("isEpisode", it.isEpisode)
                        put("rawFilename", it.originalFilename)
                        put("isVideo", it.isVideo)
                        put("duration", it.displayDuration)
                        put("hidden", it.hidden)
                        put("thumbUrl", if (it.isVideo) it.posterImageUrl else it.thumbnailUrl(240, 140))
                        put("hasLogo", !it.titleLogoUrl.isNullOrBlank())
                        put("matchedSource", it.movieMetadata?.source ?: "")
                    }
                )
            }
            albumsArr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("url", a.url)
                    put("title", a.displayTitle)
                    put("type", a.type.name)
                    put("enabled", a.enabled)
                    put("itemCount", a.itemCount)
                    put("items", itemsArr)
                }
            )
        }
        return JSONObject().apply {
            put("albums", albumsArr)
            put("moviesCount", activeRepo.getMovieItems().size)
            put("showsCount", activeRepo.buildShows().size)
            put("photosCount", activeRepo.getPhotoItems().size)
            put("tmdbApiKey", activeRepo.tmdbApiKey)
            put("quality", activeRepo.defaultQuality.name)
        }.toString()
    }

    private fun findLocalIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            val candidates = mutableListOf<String>()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            candidates.add(ip)
                        }
                    }
                }
            }
            candidates.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val AUTH_COOKIE_NAME = "ps_auth"

        fun generatePin(): String {
            return (1000 + SecureRandom().nextInt(9000)).toString()
        }

        fun extractQueryParam(query: String, name: String): String {
            if (query.isBlank()) return ""
            for (pair in query.split("&")) {
                val eq = pair.indexOf('=')
                val k = if (eq >= 0) pair.substring(0, eq) else pair
                if (k == name && eq >= 0) {
                    return runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrDefault("")
                }
            }
            return ""
        }

        fun extractCookie(cookieHeader: String, name: String): String {
            if (cookieHeader.isBlank()) return ""
            for (part in cookieHeader.split(";")) {
                val eq = part.indexOf('=')
                if (eq > 0 && part.substring(0, eq).trim() == name) {
                    return part.substring(eq + 1).trim()
                }
            }
            return ""
        }

        fun isPinMatch(expectedPin: String, providedPin: String): Boolean {
            if (expectedPin.isBlank() || providedPin.isBlank()) return false
            return MessageDigest.isEqual(
                expectedPin.toByteArray(StandardCharsets.UTF_8),
                providedPin.trim().toByteArray(StandardCharsets.UTF_8)
            )
        }

        fun isAuthorizedRequest(
            expectedPin: String,
            queryPin: String = "",
            headerPin: String = "",
            cookiePin: String = ""
        ): Boolean {
            return isPinMatch(expectedPin, queryPin) ||
                isPinMatch(expectedPin, headerPin) ||
                isPinMatch(expectedPin, cookiePin)
        }

        private val PIN_GATE_HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pixel Streamer · Enter Pairing PIN</title>
<style>
  body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0b0b0f;color:#f5f5fa;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;padding:20px}
  .box{width:100%;max-width:360px;background:#14141d;border:1px solid rgba(255,255,255,.12);border-radius:16px;padding:24px;text-align:center}
  h1{margin:0 0 8px;font-size:18px;letter-spacing:1.2px;font-family:monospace;color:#00e5a3}
  p{margin:0 0 16px;font-size:13px;color:#9696a8}
  input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid rgba(255,255,255,.15);background:#0d0d13;color:#fff;font-size:18px;text-align:center;letter-spacing:6px}
  button{margin-top:12px;width:100%;background:linear-gradient(135deg,#00e5a3,#00d4ff);color:#091014;border:0;border-radius:99px;padding:11px;font-weight:700;font-size:14px;cursor:pointer}
</style>
</head>
<body>
  <form class="box" method="get" action="/">
    <h1>PIXEL STREAMER</h1>
    <p>Enter the 4-digit pairing PIN shown in the TV / Tablet URL, or scan the QR code.</p>
    <input name="pin" type="tel" inputmode="numeric" maxlength="6" placeholder="••••" autofocus required>
    <button type="submit">Connect</button>
  </form>
</body>
</html>
""".trimIndent()

        private val COMPANION_HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pixel Streamer TV · Companion</title>
<style>
  :root{--bg:#0b0b0f;--card:#14141d;--glass:#1d1d29;--accent:#00e5a3;--cyan:#00d4ff;--text:#f5f5fa;--muted:#9696a8;--border:rgba(255,255,255,.1)}
  *{box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif}
  body{margin:0;background:radial-gradient(circle at top right,rgba(0,229,163,.12),transparent 42%),radial-gradient(circle at top left,rgba(0,212,255,.08),transparent 36%),var(--bg);color:var(--text);padding:16px;max-width:740px;margin-inline:auto}
  .topbar{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:14px;flex-wrap:wrap}
  .brand{display:flex;align-items:center;gap:10px}
  .px-logo{width:34px;height:34px;border-radius:8px;background:linear-gradient(135deg,var(--accent),var(--cyan));color:#081014;font-weight:900;font-family:monospace;font-size:16px;display:grid;place-items:center;box-shadow:0 4px 18px rgba(0,229,163,.25)}
  h1{margin:0;font-size:20px;letter-spacing:1.4px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
  .sub{color:var(--muted);font-size:12px}
  .stats{display:flex;gap:8px;flex-wrap:wrap}
  .stat{background:var(--glass);border:1px solid var(--border);border-radius:99px;padding:5px 11px;font-size:12px;color:#d8d8e6;font-weight:600}
  .card{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:15px;margin-bottom:14px;backdrop-filter:blur(12px)}
  .card-title{font-size:14px;font-weight:700;letter-spacing:.4px}
  label{display:block;font-size:12px;color:var(--muted);margin:10px 0 4px}
  input,select{width:100%;padding:11px 13px;border-radius:10px;border:1px solid var(--border);background:#0d0d13;color:var(--text);font-size:14px;outline:none}
  input:focus,select:focus{border-color:var(--accent)}
  button{background:linear-gradient(135deg,var(--accent),var(--cyan));color:#091014;border:0;border-radius:99px;padding:10px 18px;font-weight:700;font-size:13px;cursor:pointer}
  button.sec{background:var(--glass);color:var(--text);border:1px solid var(--border)}
  button.sm{padding:6px 11px;font-size:12px}
  .row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:10px}
  .alb-head{display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap}
  .pill{font-size:11px;padding:3px 9px;border-radius:99px;background:rgba(0,229,163,.14);color:var(--accent);font-weight:600}
  .pill.ep{background:rgba(0,212,255,.15);color:var(--cyan)}
  .pill.logo{background:rgba(255,255,255,.12);color:#fff}
  .item{display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid rgba(255,255,255,.07);font-size:13px}
  .thumb{width:44px;height:62px;border-radius:7px;object-fit:cover;background:#0a0a0f;flex-shrink:0;border:1px solid rgba(255,255,255,.08)}
  .item-info{flex:1;min-width:0}
  .item-title{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .item-meta{font-size:11px;color:var(--muted);margin-top:2px;display:flex;gap:6px;flex-wrap:wrap;align-items:center}
  .actions{display:flex;gap:6px;flex-shrink:0}
  #msg{font-size:13px;margin:0 0 10px;color:var(--accent);min-height:18px;font-weight:600}
  .err{color:#ff7b72!important}
  .search-wrap{display:flex;gap:8px;align-items:center;margin-bottom:14px}
  .hidden-item{opacity:.48}
</style>
</head>
<body>
  <div class="topbar">
    <div class="brand">
      <div class="px-logo">PS</div>
      <div>
        <h1>PIXEL STREAMER</h1>
        <div class="sub">TV Library &amp; Remote Companion · No Sign-In Required</div>
      </div>
    </div>
    <div class="stats" id="stats"></div>
  </div>
  <div id="msg"></div>

  <div class="card">
    <div class="card-title">Add Google Photos Shared Album</div>
    <label>Shared Album Link (photos.app.goo.gl/... or photos.google.com/share/...)</label>
    <input id="url" placeholder="https://photos.app.goo.gl/...">
    <div class="row">
      <div style="flex:1;min-width:160px">
        <label>Shelf Name (Optional)</label>
        <input id="cname" placeholder="e.g. Movies, Breaking Bad, Family">
      </div>
      <div style="width:170px">
        <label>Library Mode</label>
        <select id="atype">
          <option value="AUTO">Auto (Movies + Shows)</option>
          <option value="MOVIES">Movies Only</option>
          <option value="SHOWS">TV Shows (Chronological)</option>
          <option value="PHOTOS">Photos Only</option>
        </select>
      </div>
    </div>
    <div class="row">
      <button onclick="addAlbum()">+ Add &amp; Sync to TV</button>
      <button class="sec" onclick="syncAll()">↻ Refresh All</button>
    </div>
  </div>

  <div class="search-wrap">
    <input id="q" placeholder="🔍  Search movies, shows, episodes, or filenames..." oninput="renderAlbums()">
    <button class="sec" onclick="document.getElementById('q').value='';renderAlbums()">Clear</button>
  </div>

  <div id="albums"></div>

  <div class="card">
    <div class="card-title">Artwork, Title Logos &amp; Playback Quality</div>
    <label>TMDB API Key or Read Token (Optional — IMDb + Cinemeta transparent PNG logos work automatically)</label>
    <input id="tmdb" placeholder="Optional TMDB API v3 key or v4 token">
    <label>Default Stream Quality</label>
    <select id="qual">
      <option value="AUTO">Auto (Best Seekable MP4)</option>
      <option value="Q1080P">1080p Full HD</option>
      <option value="Q720P">720p HD</option>
      <option value="Q360P">360p</option>
      <option value="ORIGINAL">Original File (=dv)</option>
    </select>
    <div class="row"><button class="sec" onclick="saveSettings()">Save Settings</button></div>
  </div>

<script>
let state = { albums: [], moviesCount: 0, showsCount: 0, photosCount: 0 };
const urlPin = new URLSearchParams(window.location.search).get('pin') || '';
const msg = (t, isErr=false) => { const el=document.getElementById('msg'); el.textContent=t; el.className=isErr?'err':''; };
async function api(p, body){
  const headers = Object.assign({}, urlPin ? {'X-PS-Pin': urlPin} : {}, body ? {'Content-Type': 'application/json'} : {});
  const opts = { credentials: 'same-origin', headers };
  if(body){ opts.method='POST'; opts.body=JSON.stringify(body); }
  const r = await fetch(p, opts);
  const j = await r.json();
  if(!r.ok || j.ok===false) throw new Error(j.error || 'Request failed');
  return j;
}
async function load(){
  state = await api('/api/state');
  document.getElementById('tmdb').value = state.tmdbApiKey || '';
  document.getElementById('qual').value = state.quality || 'AUTO';
  document.getElementById('stats').innerHTML = `
    <span class="stat">${'$'}{state.moviesCount || 0} Movies</span>
    <span class="stat">${'$'}{state.showsCount || 0} Shows</span>
    <span class="stat">${'$'}{state.photosCount || 0} Photos</span>
  `;
  renderAlbums();
}
function renderAlbums(){
  const box = document.getElementById('albums');
  const q = (document.getElementById('q').value || '').trim().toLowerCase();
  if(!state.albums.length){
    box.innerHTML='<div class="card">No albums added yet. Paste a Google Photos shared album link above to start streaming on your TV.</div>';
    return;
  }
  const html = state.albums.map(a => {
    const filtered = a.items.filter(it => {
      if(!q) return true;
      return (it.title||'').toLowerCase().includes(q)
        || (it.seriesTitle||'').toLowerCase().includes(q)
        || (it.rawFilename||'').toLowerCase().includes(q)
        || (it.episodeCode||'').toLowerCase().includes(q);
    });
    if(q && !filtered.length && !(a.title||'').toLowerCase().includes(q)) return '';
    return `
      <div class="card">
        <div class="alb-head">
          <div>
            <strong>${'$'}{esc(a.title)}</strong>
            <span class="pill">${'$'}{a.type} · ${'$'}{filtered.length}${'$'}{q ? '/'+a.itemCount : ''} items</span>
          </div>
          <div class="row" style="margin:0">
            <button class="sec sm" onclick="toggleAlbum('${'$'}{a.id}', ${'$'}{!a.enabled})">${'$'}{a.enabled ? 'Visible' : 'Hidden'}</button>
            <button class="sec sm" onclick="delAlbum('${'$'}{a.id}')">Remove</button>
          </div>
        </div>
        <div style="margin-top:10px">
          ${'$'}{filtered.map(it => `
            <div class="item ${'$'}{it.hidden ? 'hidden-item' : ''}">
              <img class="thumb" loading="lazy" src="${'$'}{escAttr(it.thumbUrl || '')}" alt="">
              <div class="item-info">
                <div class="item-title">${'$'}{esc(it.title)}</div>
                <div class="item-meta">
                  ${'$'}{it.isEpisode ? `<span class="pill ep">${'$'}{esc(it.seriesTitle || 'Show')} ${'$'}{esc(it.episodeCode || '')}</span>` : ''}
                  <span>${'$'}{it.isVideo ? 'Video' : 'Photo'}${'$'}{it.duration ? ' · '+esc(it.duration) : ''}</span>
                  ${'$'}{it.hasLogo ? '<span class="pill logo">Title Logo</span>' : ''}
                  ${'$'}{it.matchedSource ? '<span>Art: '+esc(it.matchedSource)+'</span>' : ''}
                </div>
              </div>
              <div class="actions">
                ${'$'}{it.isVideo ? `<button class="sec sm" onclick="renameItem('${'$'}{a.id}','${'$'}{it.id}','${'$'}{escAttr(it.seriesTitle || it.title)}')">Fix Art</button>` : ''}
                <button class="sec sm" onclick="toggleItem('${'$'}{a.id}','${'$'}{it.id}',${'$'}{!it.hidden})">${'$'}{it.hidden ? 'Show' : 'Hide'}</button>
              </div>
            </div>
          `).join('')}
        </div>
      </div>
    `;
  }).join('');
  box.innerHTML = html || '<div class="card">No items matched "' + esc(q) + '".</div>';
}
const esc = s => (s||'').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const escAttr = s => esc(s);
async function addAlbum(){
  const url = document.getElementById('url').value.trim();
  if(!url) return msg('Paste a Google Photos shared album link first', true);
  msg('Syncing album, detecting episodes, and fetching Title Logos & posters...');
  try {
    const r = await api('/api/albums', {url, customTitle: document.getElementById('cname').value, type: document.getElementById('atype').value});
    document.getElementById('url').value='';
    msg('Added: ' + r.album);
    load();
  } catch(e){ msg(e.message, true); }
}
async function syncAll(){ msg('Refreshing albums...'); try{ await api('/api/sync',{}); msg('Albums refreshed!'); load(); }catch(e){ msg(e.message,true);} }
async function toggleAlbum(id, en){ await api('/api/albums/toggle',{albumId:id, enabled:en}); load(); }
async function delAlbum(id){ if(confirm('Remove this album from TV?')){ await api('/api/albums/delete',{albumId:id}); load(); } }
async function toggleItem(aid, iid, h){ await api('/api/items/toggle',{albumId:aid, itemId:iid, hidden:h}); load(); }
async function renameItem(aid, iid, cur){
  const t = prompt('Enter exact movie or show title to fetch Title Logo & poster art:', cur);
  if(t === null) return;
  msg('Matching Title Logo & artwork for ' + t + '...');
  const r = await api('/api/items/rename',{albumId:aid, itemId:iid, title:t});
  msg(r.matched ? 'Matched: ' + r.matched : 'Updated title');
  load();
}
async function saveSettings(){
  await api('/api/settings',{tmdbApiKey: document.getElementById('tmdb').value, quality: document.getElementById('qual').value});
  msg('Settings saved!');
}
load();
</script>
</body>
</html>
""".trimIndent()
    }
}
