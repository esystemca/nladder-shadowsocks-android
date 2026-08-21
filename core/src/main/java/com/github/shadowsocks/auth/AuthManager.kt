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
                        errorJson.optString("message", "Login failed (Status $responseCode)")
                    } catch (_: Exception) {
                        "Login failed (Status $responseCode)"
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

    fun checkSubscription(): Result<Boolean> {
        val token = DataStore.accessToken
        if (token.isNullOrEmpty()) {
            DataStore.hasValidSubscription = false
            return Result.success(false)
        }

        val urlString = "${ApiConfig.HOST_URL}/billing/subscription"
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            val tokenType = DataStore.tokenType ?: "Bearer"
            connection.setRequestProperty("Authorization", "$tokenType $token")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
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
        DataStore.accessToken = null
        DataStore.refreshToken = null
        DataStore.tokenType = null
        DataStore.userEmail = null
        DataStore.hasValidSubscription = false
        ProfileManager.clear()
    }
}
