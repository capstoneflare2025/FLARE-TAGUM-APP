package com.example.flare_capstone

import android.os.AsyncTask
import android.util.Log
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.collections.filter
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlin.text.isNotBlank
import kotlin.text.isNotEmpty
import kotlin.text.lowercase
import kotlin.text.trim

class FetchBarangayAddressTask(
    private val activity: Any, // Now can be any activity (FireLevelActivity or OtherEmergencyActivity)
    private val latitude: Double,
    private val longitude: Double,
    private var fetchedAddress: String? = null
) : AsyncTask<Void, Void, String?>() {

    override fun doInBackground(vararg params: Void?): String? {
        return try {
            // OpenStreetMap Nominatim reverse geocode
            val url = URL(
                "https://nominatim.openstreetmap.org/reverse?" +
                        "lat=$latitude&lon=$longitude&format=json&addressdetails=1&zoom=18"
            )
            val urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.requestMethod = "GET"
            urlConnection.connect()

            val inputStreamReader = InputStreamReader(urlConnection.inputStream)
            val response = StringBuilder()
            var data = inputStreamReader.read()
            while (data != -1) {
                response.append(data.toChar())
                data = inputStreamReader.read()
            }

            val jsonResponse = JSONObject(response.toString())
            val address = jsonResponse.getJSONObject("address")

            Log.d("FetchBarangayAddress", "Full address JSON: $address")

            val neighbourhoodKeys = listOf(
                "neighbourhood", "suburb", "quarter", "hamlet",
                "croft", "block", "locality", "residential"
            )
            val barangayKeys = listOf("village", "barangay")
            val cityKeys = listOf("city", "town")

            fun findFirstValid(keys: List<String>): String {
                for (key in keys) {
                    val value = address.optString(key).trim()
                    Log.d("FetchBarangayAddress", "Checking $key: '$value'")
                    if (value.isNotEmpty() && value.lowercase() != "null") {
                        Log.d("FetchBarangayAddress", "Selected $key = '$value'")
                        return value
                    }
                }
                return "N/A"
            }

            val neighbourhoodRaw = findFirstValid(neighbourhoodKeys)
            val barangay = findFirstValid(barangayKeys)
            val city = findFirstValid(cityKeys)

            // Extract purok from raw neighborhood string
            val purok = extractPurok(neighbourhoodRaw)

            // Use extracted purok if found, else use neighbourhood raw
            val neighbourhood = purok ?: if (neighbourhoodRaw != "N/A") neighbourhoodRaw else null

            // Compose final address string without province
            val addressParts = listOfNotNull(neighbourhood, barangay, city)
                .filter { it.isNotBlank() && it.lowercase() != "n/a" }

            return if (addressParts.isNotEmpty()) {
                addressParts.joinToString(", ")
            } else {
                null
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("FetchBarangayAddress", "Exception fetching address: ${e.message}")
            null
        }
    }

    override fun onPostExecute(result: String?) {
        super.onPostExecute(result)
        // Check the activity type and call the appropriate method to handle the fetched address
        when (activity) {
            is FireLevelActivity -> activity.handleFetchedAddress(result)
            is OtherEmergencyActivity -> activity.handleFetchedAddress(result)
            else -> Log.e("FetchBarangayAddress", "Unsupported activity")
        }
    }

    /**
     * Extract "Purok <name>" from a string.
     * Matches "Purok" followed by 1 or 2 words, ignoring extra words like "Chapel".
     */
    private fun extractPurok(input: String?): String? {
        if (input == null) return null

        // Regex: Purok + 1 or 2 words (letters/numbers), ignoring extras after that
        val regex = Regex("""(?i)(Purok\s+\w+(\s+\w+)?)""")
        val match = regex.find(input)
        return match?.value
    }
}
