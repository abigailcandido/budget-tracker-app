package com.abigailcandido.budgettracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
)

@Serializable
data class UserResponse(
    val id: Int,
    val email: String,
    @SerialName("has_pin") val hasPin: Boolean,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class SetPinRequest(
    val password: String,
    val pin: String,
)

@Serializable
data class VerifyPinRequest(
    val pin: String,
)

@Serializable
data class ApiErrorBody(
    val detail: JsonElement? = null,
)

/**
 * FastAPI reports errors in two different shapes. A raised HTTPException sends
 * "detail" as a plain string, while a request that fails Pydantic validation
 * sends it as a list of objects, each with a "msg" field. Treating either one
 * as the other crashes the parser, so both are handled here.
 */
fun ApiErrorBody.readableMessage(fallback: String): String {
    val element = detail ?: return fallback
    return when (element) {
        is JsonPrimitive -> element.contentOrNull ?: fallback
        is JsonArray -> (element.firstOrNull() as? JsonObject)
            ?.get("msg")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?: fallback
        else -> fallback
    }
}

@Serializable
data class PasswordResetEmailRequest(
    val email: String,
)

@Serializable
data class MessageResponse(
    val detail: String,
)