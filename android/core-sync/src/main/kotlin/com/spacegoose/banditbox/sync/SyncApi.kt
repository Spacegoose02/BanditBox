package com.spacegoose.banditbox.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The server's TV endpoints (docs/api.md). Calls block; run them off the main thread. */
interface SyncApi {
    fun me(): Me
    fun layout(profileId: String): ServerLayout
    fun sendOps(profileId: String, baseVersion: Long, ops: List<Op>): OpsResult
    fun seed(layouts: List<JsonObject>): List<ServerLayout>
    fun createProfile(doc: JsonObject): ServerLayout
    fun deleteProfile(profileId: String)

    @Serializable
    data class Me(val device: Device, val profiles: List<ProfileInfo>, val fresh: Boolean = false)

    @Serializable
    data class Device(val id: String, val name: String = "")

    @Serializable
    data class ProfileInfo(val id: String, val name: String = "", val version: Long)

    @Serializable
    data class ServerLayout(val profileId: String, val version: Long, val doc: JsonObject)

    @Serializable
    data class Rejected(val opId: String, val reason: String, val detail: String? = null)

    @Serializable
    data class OpsResult(
        val version: Long,
        val applied: List<String> = emptyList(),
        val rejected: List<Rejected> = emptyList(),
        val doc: JsonObject,
    )

    @Serializable
    data class PairStart(val pairId: String, val code: String, val qrUrl: String, @SerialName("expiresAt") val expiresAt: String)

    /** An app installed on the TV, for the web editor's app picker. */
    @Serializable
    data class AppReport(val packageName: String, val label: String, val tv: Boolean)

    @Serializable
    data class PairResult(val status: String, val deviceId: String? = null, val token: String? = null)
}

/** The server said the token is no longer valid: the TV was unpaired. */
class RevokedException : Exception("this TV is no longer paired")

/** Any other answer the server shouldn't give, with its HTTP status. */
class ServerException(val status: Int, message: String) : Exception(message)
