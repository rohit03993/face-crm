package com.school.faceverify.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persist failed result uploads for retry when offline. */
class OfflineResultQueue(context: Context) {
    private val file = File(context.filesDir, "offline_results.json")

    @Synchronized
    fun enqueue(requestId: String, score: Float, passed: Boolean, failPath: String?) {
        val arr = load()
        arr.put(
            JSONObject()
                .put("request_id", requestId)
                .put("score", score.toDouble())
                .put("passed", passed)
                .put("fail_path", failPath),
        )
        save(arr)
    }

    @Synchronized
    fun drain(send: (requestId: String, score: Float, passed: Boolean, failPath: String?) -> Boolean) {
        val arr = load()
        if (arr.length() == 0) return
        val remaining = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val ok = send(
                o.getString("request_id"),
                o.getDouble("score").toFloat(),
                o.getBoolean("passed"),
                if (o.isNull("fail_path")) null else o.getString("fail_path"),
            )
            if (!ok) remaining.put(o)
        }
        save(remaining)
    }

    private fun load(): JSONArray {
        if (!file.exists()) return JSONArray()
        return try {
            JSONArray(file.readText())
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun save(arr: JSONArray) {
        file.writeText(arr.toString())
    }
}
