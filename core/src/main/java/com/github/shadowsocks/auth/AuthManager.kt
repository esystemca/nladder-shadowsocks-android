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

    fun logout() {
        DataStore.accessToken = null
        DataStore.refreshToken = null
        DataStore.tokenType = null
        DataStore.userEmail = null
        ProfileManager.clear()
    }
}
