/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2026 by Shadowsocks-Android                                 *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.auth

import com.github.shadowsocks.Core
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.ApiConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object AuthManager {
    data class AuthResponse(
        val tokenType: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long
    )

    fun login(email: String, password: String): Result<AuthResponse> {
        val urlString = "${ApiConfig.HOST_URL}/auth/login"
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            val requestJson = JSONObject().apply {
                put("email", email)
                put("password", password)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(responseText)
                val tokenType = jsonObj.optString("tokenType", "Bearer")
                val accessToken = jsonObj.optString("accessToken", "")
                val refreshToken = jsonObj.optString("refreshToken", "")
                val expiresIn = jsonObj.optLong("expiresIn", 3600)

                DataStore.accessToken = accessToken
                DataStore.refreshToken = refreshToken
                DataStore.tokenType = tokenType
                DataStore.userEmail = email

                Result.success(AuthResponse(tokenType, accessToken, refreshToken, expiresIn))
            } else {
                val errorText = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }

                val errorMessage = if (errorText.isNotEmpty()) {
                    try {
                        val errorJson = JSONObject(errorText)
                        when {
                            errorJson.has("message") && errorJson.getString("message").isNotEmpty() -> errorJson.getString("message")
                            errorJson.has("error_description") && errorJson.getString("error_description").isNotEmpty() -> errorJson.getString("error_description")
                            errorJson.has("error") && errorJson.getString("error").isNotEmpty() -> errorJson.getString("error")
                            errorJson.has("msg") && errorJson.getString("msg").isNotEmpty() -> errorJson.getString("msg")
                            errorJson.has("errorMessage") && errorJson.getString("errorMessage").isNotEmpty() -> errorJson.getString("errorMessage")
                            else -> "Login failed (Status $responseCode)"
                        }
                    } catch (_: Exception) {
                        val trimmed = errorText.trim()
                        if (trimmed.isNotEmpty() && !trimmed.contains("<!DOCTYPE html", ignoreCase = true) && !trimmed.contains("<html", ignoreCase = true)) {
                            trimmed
                        } else {
                            "Login failed (Status $responseCode)"
                        }
                    }
                } else {
                    "Login failed (Status $responseCode)"
                }

                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Timber.w(e, "Login request failed")
            Result.failure(e)
        }
    }

    fun refresh(): Result<AuthResponse> {
        val refreshToken = DataStore.refreshToken
        if (refreshToken.isNullOrEmpty()) {
            logout()
            return Result.failure(Exception("No refresh token available"))
        }

        val urlString = "${ApiConfig.HOST_URL}/auth/refresh"
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            // Send ONLY the JSON body with the single key "refreshToken"
            val requestJson = JSONObject().apply {
                put("refreshToken", refreshToken)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(responseText)
                val tokenType = jsonObj.optString("tokenType", "Bearer")
                val accessToken = jsonObj.optString("accessToken", "")
                val newRefreshToken = jsonObj.optString("refreshToken", "")
                val expiresIn = jsonObj.optLong("expiresIn", 3600)

                DataStore.accessToken = accessToken
                DataStore.refreshToken = newRefreshToken
                DataStore.tokenType = tokenType

                Result.success(AuthResponse(tokenType, accessToken, newRefreshToken, expiresIn))
            } else {
                val errorText = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }
                Timber.e("Refresh failed. Code: $responseCode, Error: $errorText")
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    Timber.e("Refresh token has expired or is invalid. Force logging out.")
                    logout()
                }
                Result.failure(Exception("Refresh failed with code: $responseCode, msg: $errorText"))
            }
        } catch (e: Exception) {
            Timber.w(e, "Refresh request failed")
            Result.failure(e)
        }
    }

    fun checkSubscription(): Result<Boolean> {
        var token = DataStore.accessToken
        if (token.isNullOrEmpty()) {
            DataStore.hasValidSubscription = false
            return Result.success(false)
        }

        val urlString = "${ApiConfig.HOST_URL}/billing/subscription"
        return try {
            val url = URL(urlString)
            var connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            var tokenType = DataStore.tokenType ?: "Bearer"
            connection.setRequestProperty("Authorization", "$tokenType $token")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            var responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                Timber.w("Access token expired for subscription check, trying to refresh...")
                val refreshResult = refresh()
                if (refreshResult.isSuccess) {
                    token = DataStore.accessToken
                    tokenType = DataStore.tokenType ?: "Bearer"
                    connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Authorization", "$tokenType $token")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    responseCode = connection.responseCode
                } else {
                    Timber.e("Refresh token failed/expired for subscription check.")
                    DataStore.hasValidSubscription = false
                    return Result.success(false)
                }
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = try { JSONObject(responseText) } catch (_: Exception) { null }
                val isActive = if (jsonObj != null) {
                    when {
                        jsonObj.has("active") -> jsonObj.optBoolean("active")
                        jsonObj.has("valid") -> jsonObj.optBoolean("valid")
                        jsonObj.has("hasSubscription") -> jsonObj.optBoolean("hasSubscription")
                        jsonObj.has("subscribed") -> jsonObj.optBoolean("subscribed")
                        jsonObj.has("isSubscribed") -> jsonObj.optBoolean("isSubscribed")
                        jsonObj.has("isValid") -> jsonObj.optBoolean("isValid")
                        jsonObj.has("status") -> {
                            val st = jsonObj.optString("status", "").uppercase()
                            st == "ACTIVE" || st == "SUBSCRIBED" || st == "VALID" || st == "OK" || st == "PAID" || st == "RUNNING"
                        }
                        else -> true
                    }
                } else true

                if (jsonObj != null) {
                    DataStore.subPlanName = jsonObj.optString("planName", jsonObj.optString("plan_name", jsonObj.optString("plan", "")))
                    DataStore.subStatus = jsonObj.optString("status", "")
                    DataStore.subBillingInterval = jsonObj.optString("billingInterval", jsonObj.optString("billing_interval", ""))
                    DataStore.subCapLabel = jsonObj.optString("capLabel", jsonObj.optString("cap_label", jsonObj.optString("cap", "")))
                    DataStore.subStartedAt = jsonObj.optString("startedAt", jsonObj.optString("started_at", ""))
                    DataStore.subExpiredAt = jsonObj.optString("expiredAt", jsonObj.optString("expired_at", jsonObj.optString("expiresAt", jsonObj.optString("endsAt", ""))))
                } else {
                    DataStore.subPlanName = null
                    DataStore.subStatus = null
                    DataStore.subBillingInterval = null
                    DataStore.subCapLabel = null
                    DataStore.subStartedAt = null
                    DataStore.subExpiredAt = null
                }

                DataStore.hasValidSubscription = isActive
                Result.success(isActive)
            } else {
                DataStore.hasValidSubscription = false
                Result.success(false)
            }
        } catch (e: Exception) {
            Timber.w(e, "Check subscription request failed")
            Result.failure(e)
        }
    }

    fun logout() {
        Core.stopService()
        DataStore.accessToken = null
        DataStore.refreshToken = null
        DataStore.tokenType = null
        DataStore.userEmail = null
        DataStore.hasValidSubscription = false
        DataStore.subPlanName = null
        DataStore.subStatus = null
        DataStore.subBillingInterval = null
        DataStore.subCapLabel = null
        DataStore.subStartedAt = null
        DataStore.subExpiredAt = null
        ProfileManager.clear()
    }
}
