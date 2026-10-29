package com.easy.streamarchive.core.extensions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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

    private data class CfSession(val userAgent: String, val cookie: String)

    private val cfCache = ConcurrentHashMap<String, Pair<Long, CfSession>>()
    private val cfTtlMs = 15 * 60 * 1000L

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

    private suspend fun warmupCloudflare(url: String): CfSession? {
        val ctx = appContext ?: return null
        val host = url.substringAfter("://").substringBefore("/").substringBefore(":")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                val webView = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }
                val ua = webView.settings.userAgentString

                fun storeSession(cookie: String): CfSession {
                    val session = CfSession(ua, cookie)
                    cfCache[host] = System.currentTimeMillis() to session
                    return session
                }

                cfCache[host]?.let { (time, session) ->
                    val fresh = System.currentTimeMillis() - time < cfTtlMs
                    val jar = cookieManager.getCookie(url).orEmpty()
                    val valid = session.cookie.isBlank() ||
                        jar.contains("cf_clearance")
                    if (fresh && valid) {
                        webView.destroy()
                        cont.resume(session)
                        return@suspendCancellableCoroutine
                    }
                }

                val existing = cookieManager.getCookie(url).orEmpty()
                if (existing.contains("cf_clearance")) {
                    webView.destroy()
                    cont.resume(storeSession(existing))
                    return@suspendCancellableCoroutine
                }

                val handler = Handler(Looper.getMainLooper())
                var finished = false
                var ticks = 0

                fun finish() {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    cookieManager.flush()
                    val cookie = cookieManager.getCookie(url).orEmpty()
                    webView.destroy()
                    if (cont.isActive) cont.resume(storeSession(cookie))
                }

                val poller = object : Runnable {
                    override fun run() {
                        ticks++
                        val cookie = cookieManager.getCookie(url).orEmpty()
                        if (cookie.contains("cf_clearance") || ticks >= 24) {
                            finish()
                        } else {
                            handler.postDelayed(this, 500)
                        }
                    }
                }

                val graceFinish = Runnable { finish() }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String) {
                        handler.removeCallbacks(graceFinish)
                        handler.postDelayed(graceFinish, 4000)
                    }
                }

                cont.invokeOnCancellation { finish() }
                val schemeEnd = url.indexOf("://")
                val pathStart = if (schemeEnd >= 0) url.indexOf('/', schemeEnd + 3) else -1
                webView.loadUrl(if (pathStart > 0) url.substring(0, pathStart) else url)
                handler.postDelayed(poller, 500)
            }
        }
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

        val session = warmupCloudflare(streamUrl)
        val headers = mutableMapOf(
            "User-Agent" to (session?.userAgent ?: userAgent),
            "Origin" to mainUrl
        )
        session?.cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }

        callback(
            ExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                referer = "$mainUrl/",
                quality = 720,
                type = ExtractorLinkType.M3U8,
                headers = headers
            )
        )
        return true
    }
}
