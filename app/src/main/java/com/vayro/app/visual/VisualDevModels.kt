package com.vayro.app.visual

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR;

    companion object {
        fun fromString(value: String): LogLevel {
            return when (value.uppercase()) {
                "DEBUG" -> DEBUG
                "INFO", "LOG" -> INFO
                "WARN", "WARNING" -> WARN
                "ERROR" -> ERROR
                else -> INFO
            }
        }
    }
}

data class RectFInfo(
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left.toDouble())
        put("top", top.toDouble())
        put("width", width.toDouble())
        put("height", height.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject): RectFInfo = RectFInfo(
            left = json.optDouble("left", 0.0).toFloat(),
            top = json.optDouble("top", 0.0).toFloat(),
            width = json.optDouble("width", 0.0).toFloat(),
            height = json.optDouble("height", 0.0).toFloat(),
        )
    }
}

data class DomElementInfo(
    val tag: String,
    val id: String? = null,
    val classes: List<String> = emptyList(),
    val selector: String = "",
    val xpath: String? = null,
    val innerText: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val computedStyles: Map<String, String> = emptyMap(),
    val rect: RectFInfo = RectFInfo(),
) {
    val displayName: String
        get() = buildString {
            append("<").append(tag.lowercase())
            if (!id.isNullOrBlank()) append("#").append(id)
            if (classes.isNotEmpty()) append(".").append(classes.joinToString("."))
            append(">")
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("tag", tag)
        put("id", id ?: JSONObject.NULL)
        put("classes", JSONArray(classes))
        put("selector", selector)
        put("xpath", xpath ?: JSONObject.NULL)
        put("innerText", innerText)
        put("attributes", JSONObject(attributes))
        put("computedStyles", JSONObject(computedStyles))
        put("rect", rect.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): DomElementInfo {
            val classesArray = json.optJSONArray("classes")
            val classList = mutableListOf<String>()
            if (classesArray != null) {
                for (i in 0 until classesArray.length()) {
                    classList.add(classesArray.getString(i))
                }
            }

            val attrsObj = json.optJSONObject("attributes")
            val attrsMap = mutableMapOf<String, String>()
            attrsObj?.keys()?.forEach { k -> attrsMap[k] = attrsObj.optString(k, "") }

            val stylesObj = json.optJSONObject("computedStyles")
            val stylesMap = mutableMapOf<String, String>()
            stylesObj?.keys()?.forEach { k -> stylesMap[k] = stylesObj.optString(k, "") }

            val rectObj = json.optJSONObject("rect")

            return DomElementInfo(
                tag = json.optString("tag", "div"),
                id = if (json.has("id") && !json.isNull("id")) json.getString("id") else null,
                classes = classList,
                selector = json.optString("selector", ""),
                xpath = if (json.has("xpath") && !json.isNull("xpath")) json.getString("xpath") else null,
                innerText = json.optString("innerText", ""),
                attributes = attrsMap,
                computedStyles = stylesMap,
                rect = if (rectObj != null) RectFInfo.fromJson(rectObj) else RectFInfo(),
            )
        }
    }
}

data class ConsoleLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val level: LogLevel = LogLevel.INFO,
    val message: String,
    val source: String? = null,
    val lineNumber: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("level", level.name)
        put("message", message)
        put("source", source ?: JSONObject.NULL)
        put("lineNumber", lineNumber ?: JSONObject.NULL)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject): ConsoleLogEntry = ConsoleLogEntry(
            id = json.optString("id", UUID.randomUUID().toString()),
            level = LogLevel.fromString(json.optString("level", "INFO")),
            message = json.optString("message", ""),
            source = if (json.has("source") && !json.isNull("source")) json.getString("source") else null,
            lineNumber = if (json.has("lineNumber") && !json.isNull("lineNumber")) json.getInt("lineNumber") else null,
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        )
    }
}

data class NetworkLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val method: String = "GET",
    val url: String,
    val statusCode: Int? = null,
    val statusText: String? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val isFailed: Boolean = (statusCode != null && statusCode >= 400) || error != null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("method", method)
        put("url", url)
        put("statusCode", statusCode ?: JSONObject.NULL)
        put("statusText", statusText ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("timestamp", timestamp)
        put("durationMs", durationMs)
        put("isFailed", isFailed)
    }

    companion object {
        fun fromJson(json: JSONObject): NetworkLogEntry = NetworkLogEntry(
            id = json.optString("id", UUID.randomUUID().toString()),
            method = json.optString("method", "GET"),
            url = json.optString("url", ""),
            statusCode = if (json.has("statusCode") && !json.isNull("statusCode")) json.getInt("statusCode") else null,
            statusText = if (json.has("statusText") && !json.isNull("statusText")) json.getString("statusText") else null,
            error = if (json.has("error") && !json.isNull("error")) json.getString("error") else null,
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            durationMs = json.optLong("durationMs", 0L),
            isFailed = json.optBoolean("isFailed", false),
        )
    }
}

data class LocalhostPortInfo(
    val port: Int,
    val serviceGuess: String,
    val isListening: Boolean = true,
    val responseTimeMs: Long = 0L,
    val url: String = "http://localhost:$port",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("port", port)
        put("serviceGuess", serviceGuess)
        put("isListening", isListening)
        put("responseTimeMs", responseTimeMs)
        put("url", url)
    }

    companion object {
        fun fromJson(json: JSONObject): LocalhostPortInfo = LocalhostPortInfo(
            port = json.getInt("port"),
            serviceGuess = json.optString("serviceGuess", "HTTP Service"),
            isListening = json.optBoolean("isListening", true),
            responseTimeMs = json.optLong("responseTimeMs", 0L),
            url = json.optString("url", "http://localhost:${json.getInt("port")}"),
        )
    }
}

data class VisualSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),
    val screenshotPath: String,
    val consoleErrorsCount: Int = 0,
    val networkErrorsCount: Int = 0,
    val domSummary: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("url", url)
        put("timestamp", timestamp)
        put("screenshotPath", screenshotPath)
        put("consoleErrorsCount", consoleErrorsCount)
        put("networkErrorsCount", networkErrorsCount)
        put("domSummary", domSummary ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): VisualSnapshot = VisualSnapshot(
            id = json.optString("id", UUID.randomUUID().toString()),
            title = json.optString("title", "Snapshot"),
            url = json.optString("url", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            screenshotPath = json.optString("screenshotPath", ""),
            consoleErrorsCount = json.optInt("consoleErrorsCount", 0),
            networkErrorsCount = json.optInt("networkErrorsCount", 0),
            domSummary = if (json.has("domSummary") && !json.isNull("domSummary")) json.getString("domSummary") else null,
        )
    }
}

data class VisualComparisonPair(
    val id: String = UUID.randomUUID().toString(),
    val beforeSnapshot: VisualSnapshot,
    val afterSnapshot: VisualSnapshot,
    val title: String = "Comparison",
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

data class ViewportPreset(
    val id: String,
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val isMobile: Boolean,
) {
    companion object {
        val RESPONSIVE = ViewportPreset("responsive", "Responsive", 0, 0, false)
        val PIXEL_8 = ViewportPreset("pixel8", "Pixel 8", 412, 915, true)
        val IPHONE_15 = ViewportPreset("iphone15", "iPhone 15", 393, 852, true)
        val IPAD_AIR = ViewportPreset("ipad", "iPad Air", 820, 1180, false)
        val DESKTOP = ViewportPreset("desktop", "Desktop 1080p", 1920, 1080, false)

        val ALL = listOf(RESPONSIVE, PIXEL_8, IPHONE_15, IPAD_AIR, DESKTOP)
    }
}
