package com.tkhskt.claude.notification.permission

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class PermissionRequest(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("transcript_path") val transcriptPath: String = "",
    val cwd: String = "",
    @SerialName("permission_mode") val permissionMode: String = "",
    @SerialName("hook_event_name") val hookEventName: String = "",
    @SerialName("tool_name") val toolName: String = "",
    @SerialName("tool_input") val toolInput: JsonObject = JsonObject(emptyMap()),
    @SerialName("permission_suggestions") val permissionSuggestions: JsonArray? = null,
)

enum class Decision { ALLOW, DENY }
