package com.abigailcandido.budgettracker.data.remote

import com.abigailcandido.budgettracker.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object ApiClient {

    val json = Json {
        ignoreUnknownKeys = true
    }

    val httpClient: HttpClient = HttpClient(OkHttp) {

        // Non-2xx responses are returned rather than thrown, so each call site
        // can decide what a 401 or a 409 means in its own context.
        expectSuccess = false

        install(ContentNegotiation) {
            json(json)
        }

        install(Logging) {
            // HEADERS, never BODY: request bodies here contain passwords and PINs,
            // and Logcat is readable by anyone with the phone plugged in.
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.BODY
        }

        install(HttpTimeout) {
            // Generous because Render's free tier sleeps when idle and can take
            // the better part of a minute to wake up on the first request.
            connectTimeoutMillis = 30_000
            requestTimeoutMillis = 90_000
            socketTimeoutMillis = 90_000
        }

        defaultRequest {
            url(BuildConfig.API_BASE_URL + "/")
            contentType(ContentType.Application.Json)
        }
    }
}