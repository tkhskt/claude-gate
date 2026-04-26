package com.tkhskt.claude.gate.permission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object HookResponse {
    private val json = Json { prettyPrint = false }

    fun forDecision(decision: Decision, updatedInput: JsonObject? = null): String {
        val behavior = when (decision) {
            Decision.ALLOW -> "allow"
            Decision.DENY -> "deny"
        }
        val body = buildJsonObject {
            putJsonObject("hookSpecificOutput") {
                put("hookEventName", "PermissionRequest")
                putJsonObject("decision") {
                    put("behavior", behavior)
                    if (decision == Decision.ALLOW && updatedInput != null) {
                        put("updatedInput", updatedInput)
                    }
                    if (decision == Decision.DENY) {
                        put("message", "Denied from menu bar app")
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), body)
    }
}
