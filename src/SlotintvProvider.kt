package com.easy.streamarchive.core.extensions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.easy.streamarchive.core.api.ExtractorLink
import com.easy.streamarchive.core.api.ExtractorLinkType
import com.easy.streamarchive.core.api.HomePageList
import com.easy.streamarchive.core.api.HomePageResponse
import com.easy.streamarchive.core.api.LoadResponse
import com.easy.streamarchive.core.api.MainApi
import com.easy.streamarchive.core.api.MainPageRequest
import com.easy.streamarchive.core.api.MovieLoadResponse
import com.easy.streamarchive.core.api.MovieSearchResponse
import com.easy.streamarchive.core.api.SearchResponse
import com.easy.streamarchive.core.api.SubtitleFile
import com.easy.streamarchive.core.api.TvType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class SlotintvPlugin : StreamPlugin() {
    override fun load(context: Context) {
        registerMainApi(SlotintvProvider(context.applicationContext))
    }
}

class SlotintvProvider(private val appContext: Context? = null) : MainApi() {

    override var name = "Slotin TV"
    override var mainUrl = "https://30slotintv.live"
    override var lang = "tr"
    override val hasMainPage = true
    override var supportedTypes = setOf(TvType.Live)

    private val dataUrl = "https://data-reality.com"

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private data class LiveItem(
        val id: String,
        val name: String,
        val detail: String,
        val posterUrl: String?,
        val isChannel: Boolean
    )

    private var cachedItems: List<LiveItem> = emptyList()

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "$mainUrl/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim()

    private fun fixUrl(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http") -> url
        else -> "$mainUrl/${url.trimStart('/')}"
    }

    private fun parseItems(html: String, isChannel: Boolean): List<LiveItem> {
        return html.split("<a class=\"single-match").drop(1).mapNotNull { block ->
            val id = Regex("""href="channel\?id=([^"&]+)""")
                .find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val home = Regex("""<div class="home">\s*(.*?)\s*</div>""")
                .find(block)?.groupValues?.get(1)?.let(::stripTags).orEmpty()
            val awayBlock = Regex("""<div class="away"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1).orEmpty()
            val event = Regex("""<div class="event">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                .find(block)?.groupValues?.get(1)?.let(::stripTags).orEmpty()
            val date = Regex("""<div class="date">(.*?)</div>""")
                .find(block)?.groupValues?.get(1)?.let(::stripTags).orEmpty()
            val images = Regex("""<img[^>]+src="([^"]+)"""")
                .findAll(block).map { it.groupValues[1] }.toList()

            val name: String
            val poster: String?
            val detail: String
            if (isChannel) {
                name = home
                poster = fixUrl(
                    Regex("""<img[^>]+src="([^"]+)"""").find(awayBlock)?.groupValues?.get(1)
                )
                detail = event.ifBlank { "7/24 Canlı" }
            } else {
                val away = stripTags(awayBlock)
                name = "$home - $away"
                poster = fixUrl(images.firstOrNull())
                detail = listOf(date, event).filter { it.isNotBlank() }.joinToString(" | ")
            }
            LiveItem(id, name, detail, poster, isChannel)
        }.distinctBy { "${it.isChannel}:${it.id}:${it.name}" }
    }

    private suspend fun fetchAll(): List<LiveItem> {
        val matches = runCatching {
            parseItems(httpGet("$dataUrl/matches.php"), isChannel = false)
        }.getOrDefault(emptyList())
        val channels = runCatching {
            parseItems(httpGet("$dataUrl/channels.php"), isChannel = true)
        }.getOrDefault(emptyList())
        return (matches + channels).also { cachedItems = it }
    }

    private fun LiveItem.toSearchResponse(): SearchResponse = MovieSearchResponse(
        name = name,
        url = "channel?id=$id",
        apiName = this@SlotintvProvider.name,
        type = TvType.Live,
        posterUrl = posterUrl
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = if (page > 1) cachedItems.ifEmpty { fetchAll() } else fetchAll()
        val matches = items.filter { !it.isChannel }.map { it.toSearchResponse() }
        val channels = items.filter { it.isChannel }.map { it.toSearchResponse() }
        return HomePageResponse(
            listOfNotNull(
                if (matches.isNotEmpty())
                    HomePageList("Maçlar", matches, isHorizontalImages = true) else null,
                if (channels.isNotEmpty())
                    HomePageList("Kanallar", channels, isHorizontalImages = true) else null
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val items = cachedItems.ifEmpty { fetchAll() }
        return items.filter { it.name.contains(query, ignoreCase = true) }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfter("id=").substringBefore("&")
        val item = cachedItems.ifEmpty { fetchAll() }
            .firstOrNull { it.id == id }
        return MovieLoadResponse(
            name = item?.name ?: "Canlı Yayın",
            url = url,
            apiName = name,
            type = TvType.Live,
            posterUrl = item?.posterUrl,
            plot = item?.detail,
            dataUrl = id
        )
    }

    // ---------- WebView proxy (Cloudflare bypass) ----------

    private inner class FetchResult(
        val status: Int,
        val contentType: String,
        val body: ByteArray
    )

    private inner class JsBridge {
        @JavascriptInterface
        fun onResult(id: String, status: Int, contentType: String, bodyB64: String) {
            val body = runCatching {
                Base64.decode(bodyB64, Base64.DEFAULT)
            }.getOrDefault(ByteArray(0))
            pending[id]?.complete(FetchResult(status, contentType, body))
        }
    }

    private val pending =
        ConcurrentHashMap<String, CompletableDeferred<FetchResult>>()

    @Volatile private var proxyView: WebView? = null
    @Volatile private var proxyPort = 0
    @Volatile private var proxyHost: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/").substringBefore(":")

    private fun rootOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart > 0) url.substring(0, pathStart) else url
    }

    private suspend fun ensureProxy(targetUrl: String) = withContext(Dispatchers.Main) {
        val ctx = appContext ?: return@withContext
        val host = hostOf(targetUrl)

        if (proxyView == null) {
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.addJavascriptInterface(JsBridge(), "AndroidBridge")
            proxyView = wv
        }
        if (proxyPort == 0) startProxyServer()

        if (proxyHost != host) {
            proxyHost = host
            suspendCancellableCoroutine<Unit> { cont ->
                var resumed = false
                fun done() {
                    if (resumed) return
                    resumed = true
                    if (cont.isActive) cont.resume(Unit)
                }
                proxyView?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        mainHandler.postDelayed({ done() }, 4000)
                    }
                }
                proxyView?.loadUrl(rootOf(targetUrl))
                mainHandler.postDelayed({ done() }, 10000)
            }
        }
    }

    private fun startProxyServer() {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        proxyPort = socket.localPort
        Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread { handleProxyRequest(client) }.start()
            }
        }.apply { isDaemon = true }.start()
        Log.d("SlotinTV", "proxy on 127.0.0.1:$proxyPort")
    }

    private fun handleProxyRequest(socket: Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val requestLine = reader.readLine() ?: return
            while (reader.readLine()?.isNotEmpty() == true) Unit // skip headers
            val target = runCatching {
                val query = requestLine.substringAfter(" ").substringBefore(" ")
                val u = query.substringAfter("u=", "")
                URLDecoder.decode(u, "UTF-8")
            }.getOrNull() ?: return

            val result = runBlocking { wvFetch(target) }
            val out = s.getOutputStream()
            if (result == null) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }
            var body = result.body
            var contentType = result.contentType
            if (target.endsWith(".m3u8", ignoreCase = true) ||
                contentType.contains("mpegurl", ignoreCase = true)
            ) {
                body = rewritePlaylist(String(body, Charsets.UTF_8), target)
                    .toByteArray(Charsets.UTF_8)
                contentType = "application/vnd.apple.mpegurl"
            }
            val head = "HTTP/1.1 ${result.status} OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
        }
    }

    private fun rewritePlaylist(playlist: String, playlistUrl: String): String {
        val base = playlistUrl.substringBeforeLast("/")
        fun absolutize(uri: String): String = when {
            uri.startsWith("http") -> uri
            uri.startsWith("/") -> rootOf(playlistUrl) + uri
            else -> "$base/$uri"
        }
        fun proxify(uri: String): String =
            "http://127.0.0.1:$proxyPort/f?u=" + URLEncoder.encode(absolutize(uri), "UTF-8")

        return playlist.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#") -> line.replace(
                    Regex("""URI="([^"]+)"""")
                ) { m -> "URI=\"${proxify(m.groupValues[1])}\"" }
                line.isBlank() -> line
                else -> proxify(line.trim())
            }
        }
    }

    private suspend fun wvFetch(url: String): FetchResult? {
        val wv = proxyView ?: return null
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<FetchResult>()
        pending[id] = deferred
        val js = """(function(){fetch("$url",{credentials:'include'})
            .then(async function(r){var b=await r.arrayBuffer();var u=new Uint8Array(b);
            var s='';for(var i=0;i<u.length;i+=16384){s+=String.fromCharCode.apply(null,
            u.subarray(i,Math.min(i+16384,u.length)))}
            AndroidBridge.onResult("$id",r.status,
            (r.headers.get('content-type')||'application/octet-stream'),btoa(s))})
            .catch(function(e){AndroidBridge.onResult("$id",-1,'','')})})()"""
        withContext(Dispatchers.Main) {
            runCatching { wv.evaluateJavascript(js, null) }
        }
        val result = withTimeoutOrNull(30_000) { deferred.await() }
        pending.remove(id)
        return result
    }

    private suspend fun probeStream(url: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Referer", "$mainUrl/")
            .build()
        runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streamUrl = if (data.startsWith("http")) {
            data
        } else {
            val base = runCatching {
                JSONObject(httpGet("$dataUrl/domain.php")).getString("baseurl")
            }.getOrNull() ?: return false
            "$base$data/mono.m3u8"
        }

        var finalUrl = streamUrl
        var headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/"
        )

        if (!probeStream(streamUrl)) {
            ensureProxy(streamUrl)
            val test = if (proxyView != null) wvFetch(streamUrl) else null
            Log.d("SlotinTV", "webview fetch -> ${test?.status}")
            if (test != null && test.status in 200..399) {
                finalUrl = "http://127.0.0.1:$proxyPort/f?u=" +
                    URLEncoder.encode(streamUrl, "UTF-8")
                headers = emptyMap()
            }
        }

        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = finalUrl,
                referer = headers["Referer"].orEmpty(),
                quality = 720,
                type = ExtractorLinkType.M3U8,
                headers = headers - "Referer"
            )
        )
        return true
    }
}
