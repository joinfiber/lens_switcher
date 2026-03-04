package com.fiber.lens_switcher

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caches discovered lens switch points in SharedPreferences.
 *
 * Keyed by device model ([Build.MODEL]) and camera direction (back/front)
 * because the same device model always has the same physical cameras with the
 * same switching behavior. The expensive probe (~8-11 seconds) only runs once
 * per device model per camera direction — ever.
 *
 * Cache is stored as a JSON array of objects:
 * ```json
 * [{"zoomRatio": 0.5, "physicalId": "3", "label": ".5x"}, ...]
 * ```
 *
 * Cache versioning: When the probe algorithm changes materially, [CACHE_VERSION]
 * is bumped. Old caches with a different version prefix are silently ignored,
 * forcing a re-probe with the improved algorithm.
 */
object LensCache {

    private const val PREFS_NAME = "lens_switcher"
    private const val TAG = "LensSwitcher"

    /**
     * Cache version — bump when the probe algorithm changes materially.
     *
     * v1: Coarse sweep only (0.1.0)
     * v2: Coarse sweep + binary search refinement (0.2.0)
     * v3: Change-aware stability detection; added front camera support (0.3.0)
     */
    private const val CACHE_VERSION = 3

    private val backCacheKey: String
        get() = "v${CACHE_VERSION}_lenses_${Build.MODEL.replace(" ", "_")}"

    private val frontCacheKey: String
        get() = "v${CACHE_VERSION}_front_lenses_${Build.MODEL.replace(" ", "_")}"

    // ─── Back camera ──────────────────────────────────────────────────────

    /**
     * Load cached back-camera lens data for the current device model.
     * Returns null if no cache exists, version doesn't match, or cache is corrupted.
     * Only returns results with 2+ lenses (single lens = no switching needed).
     */
    fun load(context: Context): List<Map<String, Any>>? = loadKey(context, backCacheKey)

    /**
     * Save back-camera lens data to cache. Only saves if there are 2+ lenses.
     */
    fun save(context: Context, lenses: List<Map<String, Any>>) {
        saveKey(context, backCacheKey, lenses)
        if (lenses.size >= 2) Log.d(TAG, "Cached ${lenses.size} back lenses for ${Build.MODEL}")
    }

    /**
     * Clear cached back-camera lens data. The next call to [LensProbe.discover]
     * will re-probe.
     */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(backCacheKey).apply()
        Log.d(TAG, "Back lens cache cleared for ${Build.MODEL}")
    }

    // ─── Front camera ─────────────────────────────────────────────────────

    /**
     * Load cached front-camera lens data for the current device model.
     * Returns null if no cache, version mismatch, or corruption.
     * Only returns results with 2+ lenses — most front cameras return null here.
     */
    fun loadFront(context: Context): List<Map<String, Any>>? = loadKey(context, frontCacheKey)

    /**
     * Save front-camera lens data to cache. Only saves if there are 2+ lenses.
     */
    fun saveFront(context: Context, lenses: List<Map<String, Any>>) {
        saveKey(context, frontCacheKey, lenses)
        if (lenses.size >= 2) Log.d(TAG, "Cached ${lenses.size} front lenses for ${Build.MODEL}")
    }

    /**
     * Clear cached front-camera lens data.
     */
    fun clearFront(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(frontCacheKey).apply()
        Log.d(TAG, "Front lens cache cleared for ${Build.MODEL}")
    }

    /**
     * Clear both back and front camera caches. Used by the settings recalibration tool.
     */
    fun clearAll(context: Context) {
        clear(context)
        clearFront(context)
    }

    // ─── Shared helpers ───────────────────────────────────────────────────

    private fun loadKey(context: Context, key: String): List<Map<String, Any>>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(key, null) ?: return null

        return try {
            val arr = JSONArray(json)
            val result = mutableListOf<Map<String, Any>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(mapOf(
                    "zoomRatio" to obj.getDouble("zoomRatio"),
                    "physicalId" to obj.getString("physicalId"),
                    "label" to obj.getString("label"),
                ))
            }
            if (result.size >= 2) result else null
        } catch (e: Exception) {
            Log.e(TAG, "Cache parse error for $key: ${e.message}")
            null
        }
    }

    private fun saveKey(context: Context, key: String, lenses: List<Map<String, Any>>) {
        if (lenses.size < 2) return

        val arr = JSONArray()
        for (lens in lenses) {
            arr.put(JSONObject().apply {
                put("zoomRatio", lens["zoomRatio"])
                put("physicalId", lens["physicalId"])
                put("label", lens["label"])
            })
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key, arr.toString()).apply()
    }
}
