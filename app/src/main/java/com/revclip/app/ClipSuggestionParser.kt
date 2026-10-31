package com.revclip.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class ClipSuggestion(
    val startSec: Double,
    val endSec: Double,
    val title: String,
    val reason: String,
    val confidence: Double
)

object ClipRangeValidator {
    fun isValid(startSec: Double, endSec: Double): Boolean =
        startSec.isFinite() && endSec.isFinite() && startSec >= 0.0 && endSec > startSec
}

object ClipTimeParser {
    private val secondsPattern = Regex("""^\d+(?:\.\d+)?$""")
    private val minuteSecondPattern = Regex("""^(\d+):([0-5]?\d(?:\.\d+)?)$""")

    fun parseSeconds(value: String): Double? {
        val text = value.trim()
        if (secondsPattern.matches(text)) {
            return text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        }
        val match = minuteSecondPattern.matchEntire(text) ?: return null
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toDoubleOrNull() ?: return null
        if (!seconds.isFinite() || seconds < 0.0 || seconds >= 60.0) return null
        return (minutes * 60.0 + seconds).takeIf { it.isFinite() }
    }

    fun formatSeconds(seconds: Double): String {
        require(seconds.isFinite() && seconds >= 0.0)
        return String.format(java.util.Locale.US, "%.3f", seconds)
            .trimEnd('0').trimEnd('.')
    }
}

object ClipSuggestionParser {
    fun parse(raw: String): List<ClipSuggestion> {
        val element = JsonParser.parseString(extractArray(raw))
        require(element.isJsonArray) { "AI response must be a JSON array." }
        val array = element.asJsonArray
        require(array.size() <= 20) { "AI response must contain no more than 20 clips." }

        return array.mapIndexed { index, item ->
            require(item.isJsonObject) { "Clip #${index + 1} must be a JSON object." }
            val obj = item.asJsonObject
            val start = requiredNumber(obj, "start_sec", index)
            val end = requiredNumber(obj, "end_sec", index)
            val title = requiredString(obj, "title", index)
            val reason = requiredString(obj, "reason", index)
            val confidence = requiredNumber(obj, "confidence", index)
            require(ClipRangeValidator.isValid(start, end)) {
                "Clip #${index + 1} has an invalid time range."
            }
            require(confidence in 0.0..1.0) {
                "Clip #${index + 1} confidence must be between 0 and 1."
            }
            ClipSuggestion(start, end, title, reason, confidence)
        }
    }

    private fun extractArray(raw: String): String {
        var text = raw.trim()
        val fence = 96.toChar().toString().repeat(3)
        if (text.startsWith(fence)) {
            val firstLineEnd = text.indexOf('\n')
            text = if (firstLineEnd >= 0) text.substring(firstLineEnd + 1) else text.removePrefix(fence)
            if (text.trimEnd().endsWith(fence)) {
                text = text.trimEnd().removeSuffix(fence)
            }
            text = text.trim()
        }
        val first = text.indexOf('[')
        val last = text.lastIndexOf(']')
        require(first >= 0 && last > first) { "AI response does not contain a JSON array." }
        return text.substring(first, last + 1)
    }

    private fun requiredNumber(obj: JsonObject, key: String, index: Int): Double {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
            "Clip #${index + 1} is missing numeric '$key'."
        }
        return value.asDouble.also {
            require(it.isFinite()) { "Clip #${index + 1} has a non-finite '$key'." }
        }
    }

    private fun requiredString(obj: JsonObject, key: String, index: Int): String {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            "Clip #${index + 1} is missing '$key'."
        }
        return value.asString.trim().also {
            require(it.isNotEmpty()) { "Clip #${index + 1} has an empty '$key'." }
        }
    }
}
