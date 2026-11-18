package com.spacegoose.banditbox.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [SyncApi] over HTTP with OkHttp. One shared [client] keeps connections and threads to a minimum. */
class HttpSyncApi(
    baseUrl: String,
    private val token: String,
    private val appVersion: String,
    private val client: OkHttpClient = sharedClient,
) : SyncApi {
    private val base = baseUrl.trimEnd('/') + "/api/v1"

    override fun me(): SyncApi.Me = get("/device/me", SyncApi.Me.serializer())

    override fun layout(profileId: String): SyncApi.ServerLayout =
        get("/sync/layout?profile=$profileId", SyncApi.ServerLayout.serializer())

    override fun sendOps(profileId: String, baseVersion: Long, ops: List<Op>): SyncApi.OpsResult {
        val body = buildJsonObject {
            put("profileId", profileId)
            put("baseVersion", baseVersion)
            put("ops", JsonArray(ops.map { it.toJson() }))
        }
        return send("POST", "/sync/ops", body, SyncApi.OpsResult.serializer())
    }

    override fun seed(layouts: List<JsonObject>): List<SyncApi.ServerLayout> =
        send("POST", "/sync/seed", JsonObject(mapOf("layouts" to JsonArray(layouts))), ListSerializer(SyncApi.ServerLayout.serializer()))

    override fun createProfile(doc: JsonObject): SyncApi.ServerLayout =
        send("POST", "/sync/profiles", doc, SyncApi.ServerLayout.serializer())

    /** Tells the server which apps are installed here. */
    fun putApps(apps: List<SyncApi.AppReport>) {
        val body = json.encodeToString(ListSerializer(SyncApi.AppReport.serializer()), apps)
        val rb = "{\"apps\":$body}".toRequestBody(jsonType)
        call(request("/device/apps").put(rb).build()).close()
    }

    /** APKs the server sent this TV, oldest first. */
    fun installs(): List<SyncApi.PendingApk> = get("/device/installs", ListSerializer(SyncApi.PendingApk.serializer()))

    /** Downloads an APK this TV was sent into [dest]. */
    fun downloadApk(sha256: String, dest: File) {
        call(request("/device/apks/$sha256").get().build()).use { res ->
            val body = res.body ?: throw IOException("empty reply")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    /** Tells the server what happened to an APK: waiting, installed, skipped or failed. */
    fun reportInstall(sha256: String, status: String, detail: String = "") {
        val body = buildJsonObject {
            put("status", status)
            put("detail", detail.take(500))
        }
        val rb = json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)
        call(request("/device/installs/$sha256").post(rb).build()).close()
    }

    /** Every widget feed from the server's connectors. */
    fun widgets(): WidgetSnapshot = call(request("/widgets").get().build()).use { res ->
        WidgetSnapshot.parse(res.body?.string() ?: throw IOException("empty reply"))
    }

    /** Where a media:sha256-<hex> picture is served. Fetch it with [authHeader]. */
    fun mediaUrl(sha256: String): String = "$base/media/$sha256"

    val authHeader: String get() = "Bearer $token"

    override fun deleteProfile(profileId: String) {
        call(request("/sync/profiles/$profileId").delete().build()).close()
    }

    /**
     * Opens the live stream while the launcher is in front. [onVersion] gets (profileId, version);
     * [onProfiles] means the profile list changed; [onWidgets] gets the widget feeds' version.
     * Close the returned socket when the launcher leaves.
     */
    fun stream(
        onVersion: (String, Long) -> Unit,
        onProfiles: () -> Unit,
        onClosed: (revoked: Boolean) -> Unit,
        onInstalls: () -> Unit = {},
        onWidgets: (Long) -> Unit = {},
    ): WebSocket {
        val req = request("/sync/stream").build()
        return streamClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
                when ((o["type"] as? JsonPrimitive)?.content) {
                    "version" -> {
                        val id = (o["profileId"] as? JsonPrimitive)?.content ?: return
                        val v = (o["version"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return
                        onVersion(id, v)
                    }
                    "profiles" -> onProfiles()
                    "installs" -> onInstalls()
                    "widgets" -> (o["version"] as? JsonPrimitive)?.content?.toLongOrNull()?.let(onWidgets)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(code == 4401)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onClosed(response?.code == 401)
        })
    }

    private fun request(path: String) = Request.Builder()
        .url(base + path)
        .header("Authorization", "Bearer $token")
        .header("X-BanditBox-App", appVersion)

    private fun <T> get(path: String, s: KSerializer<T>): T = call(request(path).get().build()).use { decode(it, s) }

    private fun <T> send(method: String, path: String, body: JsonElement, s: KSerializer<T>): T {
        val rb = json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)
        return call(request(path).method(method, rb).build()).use { decode(it, s) }
    }

    private fun call(req: Request): Response {
        val res = client.newCall(req).execute()
        if (res.code == 401) {
            res.close()
            throw RevokedException()
        }
        if (!res.isSuccessful) {
            val msg = res.body?.string().orEmpty()
            res.close()
            throw ServerException(res.code, msg.ifBlank { "HTTP ${res.code}" })
        }
        return res
    }

    private val streamClient by lazy { client.newBuilder().pingInterval(0, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build() }

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val jsonType = "application/json".toMediaType()

        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS) // a pairing long-poll waits up to 30 s
                .build()
        }

        private fun <T> decode(res: Response, s: KSerializer<T>): T {
            val text = res.body?.string() ?: throw IOException("empty reply")
            return json.decodeFromString(s, text)
        }

        /** Starts pairing: the TV shows the returned code and QR link. */
        fun startPairing(baseUrl: String, name: String, model: String, client: OkHttpClient = sharedClient): SyncApi.PairStart {
            val body = buildJsonObject { put("name", name); put("model", model) }
            val req = Request.Builder().url(baseUrl.trimEnd('/') + "/api/v1/pair/start")
                .post(json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonType)).build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw ServerException(res.code, res.body?.string().orEmpty())
                return decode(res, SyncApi.PairStart.serializer())
            }
        }

        /** Waits up to 30 s for the admin. Status "pending" means ask again; 404 becomes "expired". */
        fun waitForPairing(baseUrl: String, pairId: String, client: OkHttpClient = sharedClient): SyncApi.PairResult {
            val req = Request.Builder().url(baseUrl.trimEnd('/') + "/api/v1/pair/$pairId?wait=30").get().build()
            client.newCall(req).execute().use { res ->
                if (res.code == 404) return SyncApi.PairResult("expired")
                if (!res.isSuccessful) throw ServerException(res.code, res.body?.string().orEmpty())
                return decode(res, SyncApi.PairResult.serializer())
            }
        }

        /** Checks [baseUrl] is a BanditBox server; returns its version. */
        fun probe(baseUrl: String, client: OkHttpClient = sharedClient): String {
            val req = Request.Builder().url(baseUrl.trimEnd('/') + "/api/v1/health").get().build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw ServerException(res.code, "not a BanditBox server")
                val o = json.parseToJsonElement(res.body?.string().orEmpty()) as? JsonObject
                    ?: throw ServerException(res.code, "not a BanditBox server")
                if ((o["status"] as? JsonPrimitive)?.content != "ok") throw ServerException(res.code, "not a BanditBox server")
                return (o["version"] as? JsonPrimitive)?.content ?: "?"
            }
        }
    }
}
