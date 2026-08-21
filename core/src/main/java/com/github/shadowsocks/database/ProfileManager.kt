/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks.database

import android.os.Looper
import android.util.LongSparseArray
import com.github.shadowsocks.Core
import com.github.shadowsocks.auth.AuthManager
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.utils.ApiConfig
import com.github.shadowsocks.utils.DirectBoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL

object ProfileManager {
    interface Listener {
        fun onAdd(profile: Profile)
        fun onRemove(profileId: Long)
        fun onCleared()
        fun reloadProfiles()
    }
    var listener: Listener? = null

    data class ExpandedProfile(val main: Profile, val udpFallback: Profile?) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }

        fun toList() = listOfNotNull(main, udpFallback)
    }

    const val HOST_URL = ApiConfig.HOST_URL
    const val SERVERS_ONLINE_PATH = "/api/v1/servers/online"
    val API_URL get() = "$HOST_URL$SERVERS_ONLINE_PATH"
    private val lock = Any()

    @Volatile
    private var cachedProfiles: List<Profile> = emptyList()

    private val cacheFile by lazy { File(Core.deviceStorage.filesDir, "profiles_cache.json") }

    private fun saveToDiskCache(jsonText: String) {
        try {
            cacheFile.writeText(jsonText)
        } catch (e: Exception) {
            Timber.w(e, "Failed to write profiles to disk cache")
        }
    }

    private fun loadFromDiskCache(): List<Profile> {
        return try {
            if (cacheFile.exists()) {
                val jsonText = cacheFile.readText()
                val profiles = parseJsonProfiles(jsonText)
                if (profiles.isNotEmpty()) {
                    synchronized(lock) {
                        cachedProfiles = profiles
                    }
                }
                profiles
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read profiles from disk cache")
            emptyList()
        }
    }

    fun fetchProfilesFromApi(): List<Profile> {
        val subResult = AuthManager.checkSubscription()
        val hasValidSubscription = subResult.getOrDefault(false)
        if (!hasValidSubscription) {
            Timber.w("User does not have a valid subscription")
            synchronized(lock) {
                cachedProfiles = emptyList()
            }
            try { cacheFile.delete() } catch (_: Exception) {}
            return emptyList()
        }

        return try {
            val url = URL(API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            val token = DataStore.accessToken
            if (!token.isNullOrEmpty()) {
                val tokenType = DataStore.tokenType ?: "Bearer"
                connection.setRequestProperty("Authorization", "$tokenType $token")
            }
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val newProfiles = parseJsonProfiles(jsonText)
                if (newProfiles.isNotEmpty()) {
                    saveToDiskCache(jsonText)
                    synchronized(lock) {
                        cachedProfiles = newProfiles
                        if (DataStore.profileId == 0L) {
                            DataStore.profileId = newProfiles.first().id
                        }
                    }
                }
                newProfiles
            } else {
                if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || connection.responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    Timber.w("Server returned unauthorized status code: ${connection.responseCode}")
                    synchronized(lock) { cachedProfiles = emptyList() }
                    try { cacheFile.delete() } catch (_: Exception) {}
                    emptyList()
                } else {
                    Timber.w("API server returned status code: ${connection.responseCode}")
                    synchronized(lock) {
                        if (cachedProfiles.isEmpty()) loadFromDiskCache()
                        cachedProfiles
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch profiles from $API_URL")
            synchronized(lock) {
                if (cachedProfiles.isEmpty()) loadFromDiskCache()
                cachedProfiles
            }
        }
    }

    private fun parseJsonProfiles(jsonText: String): List<Profile> {
        val list = mutableListOf<Profile>()
        try {
            val jsonArray = JSONArray(jsonText)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val strId = obj.optString("id", "")
                val name = obj.optString("name", "")
                val countryCode = obj.optString("countryCode", "")
                val host = obj.optString("host", "")
                val port = obj.optInt("port", 8388)
                val method = obj.optString("encryptMethod", "chacha20-ietf-poly1305")
                val password = obj.optString("password", "")

                val numericId = if (strId.isNotEmpty()) {
                    val hash = strId.hashCode().toLong() and 0x7FFFFFFF
                    if (hash == 0L) (i + 1).toLong() else hash
                } else {
                    (i + 1).toLong()
                }

                val profile = Profile(
                    id = numericId,
                    name = name,
                    countryCode = countryCode,
                    host = host,
                    remotePort = port,
                    password = password,
                    method = method,
                    userOrder = i.toLong()
                )
                list.add(profile)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing profiles JSON")
        }
        return list
    }

    fun reloadProfiles() {
        GlobalScope.launch(Dispatchers.IO) {
            fetchProfilesFromApi()
            withContext(Dispatchers.Main) {
                listener?.reloadProfiles()
            }
        }
    }

    suspend fun reloadProfilesAsync(): List<Profile> = withContext(Dispatchers.IO) {
        val profiles = fetchProfilesFromApi()
        withContext(Dispatchers.Main) {
            listener?.reloadProfiles()
        }
        profiles
    }

    fun createProfile(profile: Profile = Profile()): Profile {
        synchronized(lock) {
            if (profile.id == 0L) {
                profile.id = System.currentTimeMillis()
            }
            cachedProfiles = cachedProfiles + profile
        }
        listener?.onAdd(profile)
        return profile
    }

    fun createProfilesFromJson(jsons: Sequence<InputStream>, replace: Boolean = false) {
        val feature = Core.currentProfile?.main
        jsons.asIterable().forEach { json ->
            try {
                Profile.parseJson(json.bufferedReader().readText(), feature) {
                    createProfile(it)
                }
            } catch (e: Exception) {
                Timber.w(e)
            }
        }
    }

    fun serializeToJson(profiles: List<Profile>? = getActiveProfiles()): JSONArray? {
        if (profiles == null) return null
        val lookup = LongSparseArray<Profile>(profiles.size).apply { profiles.forEach { put(it.id, it) } }
        return JSONArray(profiles.map { it.toJson(lookup) }.toTypedArray())
    }

    fun updateProfile(profile: Profile) {
        synchronized(lock) {
            cachedProfiles = cachedProfiles.map { if (it.id == profile.id) profile else it }
        }
    }

    fun getProfile(id: Long): Profile? {
        synchronized(lock) {
            if (cachedProfiles.isEmpty()) {
                loadFromDiskCache()
            }
            val found = cachedProfiles.firstOrNull { it.id == id }
            if (found != null) return found
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            fetchProfilesFromApi()
            synchronized(lock) {
                return cachedProfiles.firstOrNull { it.id == id }
            }
        }
        return synchronized(lock) { cachedProfiles.firstOrNull { it.id == id } }
    }

    fun expand(profile: Profile) = ExpandedProfile(profile, profile.udpFallback?.let { getProfile(it) })

    fun delProfile(id: Long) {
        synchronized(lock) {
            cachedProfiles = cachedProfiles.filter { it.id != id }
        }
        listener?.onRemove(id)
        if (id in Core.activeProfileIds && DataStore.directBootAware) DirectBoot.clean()
    }

    fun clear() {
        synchronized(lock) {
            cachedProfiles = emptyList()
        }
        DirectBoot.clean()
        listener?.onCleared()
    }

    fun ensureNotEmpty() {
        synchronized(lock) {
            if (cachedProfiles.isEmpty()) {
                loadFromDiskCache()
            }
            if (cachedProfiles.isNotEmpty()) {
                if (DataStore.profileId == 0L) {
                    DataStore.profileId = cachedProfiles.first().id
                }
                return
            }
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            fetchProfilesFromApi()
            synchronized(lock) {
                if (cachedProfiles.isNotEmpty() && DataStore.profileId == 0L) {
                    DataStore.profileId = cachedProfiles.first().id
                }
            }
        }
    }

    fun getActiveProfiles(): List<Profile>? {
        synchronized(lock) {
            if (cachedProfiles.isEmpty()) {
                loadFromDiskCache()
            }
            if (cachedProfiles.isNotEmpty()) return cachedProfiles
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            fetchProfilesFromApi()
            synchronized(lock) { return cachedProfiles.ifEmpty { null } }
        }
        return synchronized(lock) { cachedProfiles.ifEmpty { null } }
    }

    fun getAllProfiles(): List<Profile>? = getActiveProfiles()
}
