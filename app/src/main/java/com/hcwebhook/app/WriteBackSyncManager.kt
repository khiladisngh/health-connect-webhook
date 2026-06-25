package com.hcwebhook.app

import android.content.Context
import androidx.health.connect.client.records.MealType
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Polls each configured webhook for server-side pending write-back records, inserts
 * them into Health Connect via [WriteBackManager], and confirms successful writes
 * back to the server.
 *
 * Best-effort by design: per-record failures are logged and skipped so they never
 * break the read-direction sync that this manager is called from. The server speaks
 * snake_case with units embedded in field names; this class translates to the
 * camelCase schema [WriteBackManager] expects.
 */
class WriteBackSyncManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val writeBackManager = WriteBackManager(context)

    /**
     * Poll [webhooks] for pending writes and replay them into Health Connect.
     * Never throws (except CancellationException) — errors are logged so the
     * caller's sync result is unaffected.
     */
    suspend fun pollAndWriteBack(webhooks: List<WebhookConfig>) {
        for (config in webhooks.filter { it.isEnabled }) {
            try {
                pollWebhook(config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(config.url, "pending-writes poll failed: ${e.message}")
            }
        }
    }

    private suspend fun pollWebhook(config: WebhookConfig) {
        val pending = fetchPending(config) ?: return
        for (i in 0 until pending.length()) {
            val record = pending.optJSONObject(i) ?: continue
            val id = record.optString("id")
            if (id.isBlank()) continue

            // Only "pending" records are actionable; skip anything already processed.
            val status = record.optString("status").lowercase()
            if (status.isNotEmpty() && status != "pending") continue

            val type = record.optString("type")
            val data = record.optJSONObject("data") ?: continue

            val mapped = try {
                mapRecord(type, data)
            } catch (e: Exception) {
                logFailure(config.url, "mapping write $id ($type) failed: ${e.message}")
                continue
            }

            val result = try {
                writeBackManager.write(type, mapped)
            } catch (e: Exception) {
                logFailure(config.url, "write $id ($type) threw: ${e.message}")
                continue
            }

            if (result.isSuccess) {
                confirmWrite(config, id)
            } else {
                val msg = result.exceptionOrNull()?.message ?: "unknown error"
                logFailure(config.url, "write $id ($type) failed: $msg")
                // On failure: do not confirm — leave it pending for the next poll.
            }
        }
    }

    private fun fetchPending(config: WebhookConfig): JSONArray? {
        val request = buildRequest(config, config.url.trimEnd('/') + PATH_PENDING).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logFailure(config.url, "GET pending-writes -> HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                JSONObject(body).optJSONArray("pending") ?: JSONArray()
            }
        } catch (e: Exception) {
            logFailure(config.url, "GET pending-writes error: ${e.message}")
            null
        }
    }

    private fun confirmWrite(config: WebhookConfig, id: String) {
        val body = """{"id":"${id.escapeJson()}"}""".toRequestBody(jsonMediaType)
        val request = buildRequest(config, config.url.trimEnd('/') + PATH_CONFIRM).post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logFailure(config.url, "confirm-write $id -> HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            logFailure(config.url, "confirm-write $id error: ${e.message}")
        }
    }

    /**
     * Translate the server's snake_case schema (units embedded in names) into the
     * camelCase schema [WriteBackManager] consumes. Units line up with what
     * HealthConnectManager.insertNutrition expects, so no numeric conversion is
     * needed: energy is kcal, masses are grams, and sodium is already milligrams
     * (HC stores sodium as Mass.milligrams).
     */
    private fun mapRecord(type: String, data: JSONObject): JSONObject = when (type) {
        "nutrition" -> mapNutrition(data)
        "hydration" -> mapHydration(data)
        "weight" -> mapWeight(data)
        else -> JSONObject(data, names(data)) // unknown type: pass through verbatim
    }

    private fun mapNutrition(src: JSONObject): JSONObject = JSONObject().apply {
        copyString(src, "start_time", "startTime")
        copyString(src, "end_time", "endTime")
        copyString(src, "meal_name", "name")
        copyDouble(src, "energy_kcal", "calories")
        copyDouble(src, "protein_g", "protein")
        copyDouble(src, "carbohydrate_g", "carbs")
        copyDouble(src, "total_fat_g", "fat")
        copyDouble(src, "dietary_fiber_g", "dietaryFiber")
        copyDouble(src, "sugar_g", " sugar".trim()) // -> "sugar"
        copyDouble(src, "sugar_g", "sugar")
        // sodium_mg is milligrams; HC insertNutrition also expects milligrams -> pass-through.
        copyDouble(src, "sodium_mg", "sodium")
        mapMealType(src)?.let { put("mealType", it) }
        // Forward any richer camelCase fields the server may already provide directly
        // (vitamins/minerals), without overwriting the snake_case mappings above.
        passthroughCamelCase(src, NUTRITION_KEYS)
    }

    private fun mapHydration(src: JSONObject): JSONObject = JSONObject().apply {
        copyString(src, "start_time", "startTime")
        copyString(src, "end_time", "endTime")
        copyDouble(src, "volume_liters", "liters")
        // Allow a bare "liters" camelCase fallback too.
        if (!has("liters")) copyDouble(src, "liters", "liters")
        passthroughCamelCase(src, HYDRATION_KEYS)
    }

    private fun mapWeight(src: JSONObject): JSONObject = JSONObject().apply {
        copyString(src, "time", "time")
        copyDouble(src, "weight_kg", "kilograms")
        if (!has("kilograms")) copyDouble(src, "kilograms", "kilograms")
        passthroughCamelCase(src, WEIGHT_KEYS)
    }

    /** Maps the server's "meal_type" string to an AndroidX Health Connect MealType int. */
    private fun mapMealType(src: JSONObject): Int? {
        if (!src.has("meal_type") || src.isNull("meal_type")) return null
        val raw = src.optString("meal_type").uppercase().trim()
        return when (raw) {
            "BREAKFAST" -> MealType.MEAL_TYPE_BREAKFAST
            "LUNCH" -> MealType.MEAL_TYPE_LUNCH
            "DINNER" -> MealType.MEAL_TYPE_DINNER
            "SNACK" -> MealType.MEAL_TYPE_SNACK
            "UNKNOWN" -> MealType.MEAL_TYPE_UNKNOWN
            else -> raw.toIntOrNull() ?: MealType.MEAL_TYPE_UNKNOWN
        }
    }

    private fun buildRequest(config: WebhookConfig, url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        config.headers.forEach { (key, value) -> builder.addHeader(key, value) }
        return builder
    }

    private fun logFailure(url: String, message: String?) {
        try {
            PreferencesManager(context).addWebhookLog(
                WebhookLog(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    url = url,
                    statusCode = null,
                    success = false,
                    errorMessage = message,
                    dataType = "writeback",
                    recordCount = null,
                    responseTimeMs = null,
                    syncType = "writeback"
                )
            )
        } catch (_: Exception) {
            // Logging is best-effort; never disrupt the sync over a log failure.
        }
    }

    private fun JSONObject.copyString(src: JSONObject, srcKey: String, dstKey: String) {
        if (src.has(srcKey) && !src.isNull(srcKey)) {
            put(dstKey, src.getString(srcKey))
        }
    }

    private fun JSONObject.copyDouble(src: JSONObject, srcKey: String, dstKey: String) {
        if (src.has(srcKey) && !src.isNull(srcKey)) {
            put(dstKey, src.getDouble(srcKey))
        }
    }

    /** Copy any camelCase keys [keys] present in [src] that haven't already been set. */
    private fun JSONObject.passthroughCamelCase(src: JSONObject, keys: Set<String>) {
        for (key in keys) {
            if (!has(key) && src.has(key) && !src.isNull(key)) {
                val value = src.get(key)
                if (value !is JSONArray && value !is JSONObject) put(key, value)
            }
        }
    }

    private fun names(obj: JSONObject): Array<String> = obj.keys().asSequence().toList().toTypedArray()

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val PATH_PENDING = "/pending-writes"
        private const val PATH_CONFIRM = "/confirm-write"

        /** camelCase nutrition keys WriteBackManager understands (for direct pass-through). */
        private val NUTRITION_KEYS = setOf(
            "calories", "protein", "carbs", "fat", "startTime", "endTime", "name", "mealType",
            "saturatedFat", "monounsaturatedFat", "polyunsaturatedFat", "transFat", "dietaryFiber",
            "sugar", "cholesterol", "caffeine", "vitaminA", "vitaminB6", "vitaminB12", "vitaminC",
            "vitaminD", "vitaminE", "vitaminK", "biotin", "folate", "folicAcid", "niacin",
            "pantothenicAcid", "riboflavin", "thiamin", "calcium", "iron", "magnesium", "zinc",
            "potassium", "sodium", "phosphorus", "manganese", "copper", "selenium", "chromium",
            "iodine", "molybdenum", "chloride"
        )

        private val HYDRATION_KEYS = setOf("startTime", "endTime", "liters")
        private val WEIGHT_KEYS = setOf("time", "kilograms")
    }
}
