package dev.sparkynox.sparkytube.homefeed

import android.os.SystemClock
import dev.sparkynox.sparkytube.auth.Session
import dev.sparkynox.sparkytube.logs.LogRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A filter chip above the feed ("All", "Music", "Gaming"...). Selecting one is a continuation request. */
data class FeedChip(val title: String, val token: String?, val selected: Boolean)

data class AccountInfo(val name: String, val handle: String?, val photo: String?)

/**
 * Small client for YouTube's private InnerTube API (WEB client), for the native Home feed.
 *
 * Reworked along the lines of LunoMusic's InnerTube client:
 *  - the session (cookie, visitorData, datasync id, client version) comes from [Session]
 *  - every request carries the same headers the web client sends
 *  - failures are logged and returned as an error message instead of being swallowed, so the UI can
 *    tell "not logged in" apart from "request failed"
 *  - a logged out request still returns the generic feed rather than nothing
 */
object InnerTubeClient {

    private const val ORIGIN = Session.ORIGIN
    private const val BASE_URL = "$ORIGIN/youtubei/v1"

    // Public key the web frontend embeds in its page source, same for everyone.
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    // Only used until a login has stored the real version out of the page's ytcfg.
    private const val FALLBACK_CLIENT_VERSION = "2.20250101.00.00"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    private const val TAG = "InnerTube"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    data class HomeFeedResult(
        val items: List<HomeFeedItem>,
        val isLoggedIn: Boolean,
        val continuationToken: String? = null,
        val chips: List<FeedChip> = emptyList(),
        /** null on success; a short message when the request failed */
        val error: String? = null,
    )

    /** First page of the home feed. */
    suspend fun fetchHomeFeed(): HomeFeedResult = load { post("browse", JSONObject().put("browseId", "FEwhat_to_watch")) }

    /** Next page, or the feed for a selected chip (chips are continuation tokens too). */
    suspend fun fetchHomeFeedContinuation(token: String): HomeFeedResult =
        load { post("browse", JSONObject().put("continuation", token)) }

    private suspend fun load(request: () -> JSONObject): HomeFeedResult = withContext(Dispatchers.IO) {
        val loggedIn = Session.loggedIn
        try {
            val root = request()
            val items = ArrayList<HomeFeedItem>()
            collect(root, items)
            val result = HomeFeedResult(
                items = items.distinctBy { it.videoId ?: it.playlistId },
                isLoggedIn = loggedIn,
                continuationToken = findContinuationToken(root),
                chips = parseChips(root),
            )
            LogRecorder.d(TAG, "feed: ${result.items.size} items, ${result.chips.size} chips, more=${result.continuationToken != null}, auth=$loggedIn")
            if (result.items.isEmpty()) LogRecorder.w(TAG, "feed came back with 0 items (response shape may have changed)")
            result
        } catch (e: IOException) {
            HomeFeedResult(emptyList(), loggedIn, error = e.message ?: "network error")
        } catch (e: Exception) {
            LogRecorder.e(TAG, "feed parse failed", e)
            HomeFeedResult(emptyList(), loggedIn, error = "couldn't read the feed")
        }
    }

    /** Name and photo of the logged in account, null when logged out or on failure. */
    suspend fun fetchAccount(): AccountInfo? = withContext(Dispatchers.IO) {
        if (!Session.loggedIn) return@withContext null
        try {
            val root = post("account/account_menu", JSONObject())
            val h = findFirst(root, "activeAccountHeaderRenderer") ?: return@withContext null
            val name = text(h.optJSONObject("accountName")) ?: return@withContext null
            AccountInfo(
                name = name,
                handle = text(h.optJSONObject("channelHandle")),
                photo = lastThumb(h.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")),
            )
        } catch (e: Exception) {
            LogRecorder.w(TAG, "couldn't load the account info", e)
            null
        }
    }

    // ---------- request

    private fun post(path: String, body: JSONObject): JSONObject {
        val client = JSONObject()
            .put("clientName", "WEB")
            .put("clientVersion", Session.clientVersion ?: FALLBACK_CLIENT_VERSION)
            .put("hl", "en")
            .put("gl", Locale.getDefault().country.ifBlank { "US" })
        Session.visitorData?.let { client.put("visitorData", it) }

        val ctx = JSONObject().put("client", client)
        // brand accounts: act as the account that was picked at login
        if (Session.loggedIn) Session.dataSyncId?.let { ctx.put("user", JSONObject().put("onBehalfOfUser", it)) }
        body.put("context", ctx)

        val rb = Request.Builder()
            .url("$BASE_URL/$path?key=$API_KEY&prettyPrint=false")
            .post(body.toString().toRequestBody(jsonType))
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .header("Origin", ORIGIN)
            .header("X-Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", Session.clientVersion ?: FALLBACK_CLIENT_VERSION)
        Session.visitorData?.let { rb.header("X-Goog-Visitor-Id", it) }

        val cookie = Session.cookie
        val sapisid = Session.sapisid()
        if (cookie != null && sapisid != null) {
            rb.header("Cookie", cookie)
            rb.header("X-Goog-AuthUser", "0")
            rb.header("Authorization", sapisidHash(sapisid))
        }

        val t0 = SystemClock.elapsedRealtime()
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            val ms = SystemClock.elapsedRealtime() - t0
            if (!r.isSuccessful) {
                LogRecorder.e(TAG, "$path -> HTTP ${r.code} in ${ms}ms (auth=${Session.loggedIn}) body=${text.take(300)}")
                throw IOException("YouTube answered ${r.code}")
            }
            LogRecorder.d(TAG, "$path -> ${r.code} in ${ms}ms, ${text.length} bytes")
            return JSONObject(text)
        }
    }

    // SHA1("{timestamp} {sapisid} {origin}"), computed locally from the cookie, nothing is fetched for it
    private fun sapisidHash(sapisid: String): String {
        val t = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1").digest("$t $sapisid $ORIGIN".toByteArray(Charsets.UTF_8))
        return "SAPISIDHASH ${t}_${digest.joinToString("") { "%02x".format(it) }}"
    }

    // ---------- parsing
    // The tree is walked for known renderers instead of following fixed paths, YouTube keeps
    // moving the wrappers around them.

    private fun collect(node: Any?, out: MutableList<HomeFeedItem>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { parseVideoRenderer(it)?.let(out::add) }
                node.optJSONObject("gridVideoRenderer")?.let { parseVideoRenderer(it)?.let(out::add) }
                node.optJSONObject("lockupViewModel")?.let { parseLockup(it)?.let(out::add) }
                node.optJSONObject("radioRenderer")?.let { parsePlaylistLike(it)?.let(out::add) }
                (node.optJSONObject("playlistRenderer") ?: node.optJSONObject("compactPlaylistRenderer"))
                    ?.let { parsePlaylistLike(it)?.let(out::add) }
                node.keys().forEach { collect(node.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun parseVideoRenderer(r: JSONObject): HomeFeedItem? {
        val videoId = r.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = text(r.optJSONObject("title"))?.takeIf { it.isNotBlank() } ?: return null
        val channel = text(r.optJSONObject("ownerText")) ?: text(r.optJSONObject("shortBylineText")) ?: ""

        // lengthText is missing on many shelves, the time status overlay has it too
        val duration = text(r.optJSONObject("lengthText"))
            ?: r.optJSONArray("thumbnailOverlays")?.objs()
                ?.firstNotNullOfOrNull { it.optJSONObject("thumbnailOverlayTimeStatusRenderer") }
                ?.let { text(it.optJSONObject("text")) }
            ?: ""

        val views = text(r.optJSONObject("shortViewCountText")) ?: text(r.optJSONObject("viewCountText")) ?: ""
        val age = text(r.optJSONObject("publishedTimeText")) ?: ""

        val avatar = r.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }

        return HomeFeedItem(
            videoId = videoId,
            playlistId = null,
            title = title,
            channelName = channel,
            thumbnailUrl = lastThumb(r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")) ?: "",
            channelAvatarUrl = avatar,
            durationText = duration,
            viewCountText = listOf(views, age).filter { it.isNotBlank() }.joinToString(" • "),
        )
    }

    // YouTube's newer card layout. Written from the response shape, so it is deliberately lenient.
    private fun parseLockup(l: JSONObject): HomeFeedItem? {
        val type = l.optString("contentType")
        val id = l.optString("contentId").takeIf { it.isNotBlank() } ?: return null
        val isVideo = type.endsWith("_VIDEO")
        val isPlaylist = type.endsWith("_PLAYLIST") || type.endsWith("_MIX")
        if (!isVideo && !isPlaylist) return null

        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content")?.takeIf { it.isNotBlank() } ?: return null

        val rows = meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
            ?.objs()?.map { row ->
                row.optJSONArray("metadataParts")?.objs()
                    ?.mapNotNull { it.optJSONObject("text")?.optString("content")?.takeIf { s -> s.isNotBlank() } }
                    ?: emptyList()
            } ?: emptyList()
        val channel = rows.firstOrNull()?.joinToString(" • ") ?: ""
        val rest = rows.drop(1).flatten().joinToString(" • ")

        val thumbRoot = l.optJSONObject("contentImage")
        val thumb = findFirst(thumbRoot, "image")?.optJSONArray("sources")?.let { lastThumb(it) } ?: ""
        val badge = findFirst(thumbRoot, "thumbnailBadgeViewModel")?.optString("text")?.takeIf { it.isNotBlank() }

        val avatar = findFirst(meta.optJSONObject("image"), "avatarViewModel")
            ?.optJSONObject("image")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }

        return if (isVideo) {
            HomeFeedItem(id, null, title, channel, thumb, avatar, badge ?: "", rest)
        } else {
            HomeFeedItem(null, id, title, channel, thumb, avatar, "", rest, itemCountText = badge)
        }
    }

    private fun parsePlaylistLike(r: JSONObject): HomeFeedItem? {
        val playlistId = r.optString("playlistId").takeIf { it.isNotBlank() } ?: return null
        val title = text(r.optJSONObject("title"))?.takeIf { it.isNotBlank() } ?: return null
        val channel = text(r.optJSONObject("longBylineText")) ?: text(r.optJSONObject("shortBylineText")) ?: ""
        val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: r.optJSONObject("thumbnailRenderer")?.optJSONObject("showCustomThumbnailRenderer")
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val count = text(r.optJSONObject("videoCountShortText")) ?: text(r.optJSONObject("videoCountText"))
        return HomeFeedItem(
            videoId = r.optString("videoId").takeIf { it.isNotBlank() },
            playlistId = playlistId,
            title = title,
            channelName = channel,
            thumbnailUrl = lastThumb(thumbs) ?: "",
            durationText = "",
            viewCountText = "",
            itemCountText = count?.takeIf { it.isNotBlank() },
        )
    }

    private fun parseChips(root: JSONObject): List<FeedChip> {
        val bar = findFirst(root, "feedFilterChipBarRenderer") ?: return emptyList()
        return bar.optJSONArray("contents")?.objs()?.mapNotNull { c ->
            val r = c.optJSONObject("chipCloudChipRenderer") ?: return@mapNotNull null
            val title = text(r.optJSONObject("text")) ?: return@mapNotNull null
            val token = r.optJSONObject("navigationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")
                ?.takeIf { it.isNotBlank() }
            FeedChip(title, token, r.optBoolean("isSelected"))
        } ?: emptyList()
    }

    private fun findContinuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                    ?.optString("token")?.takeIf { it.isNotBlank() }?.let { return it }
                node.keys().forEach { k -> findContinuationToken(node.opt(k))?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findContinuationToken(node.opt(i))?.let { return it }
        }
        return null
    }

    // ---------- json helpers

    private fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text") ?: "" }.takeIf { it.isNotBlank() }
    }

    private fun lastThumb(a: JSONArray?): String? =
        a?.let { it.optJSONObject(it.length() - 1)?.optString("url")?.takeIf { u -> u.isNotBlank() } }

    private fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    /** Depth first search for the first object stored under [key]. */
    private fun findFirst(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { return it }
                node.keys().forEach { k -> findFirst(node.opt(k), key)?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findFirst(node.opt(i), key)?.let { return it }
        }
        return null
    }
}
