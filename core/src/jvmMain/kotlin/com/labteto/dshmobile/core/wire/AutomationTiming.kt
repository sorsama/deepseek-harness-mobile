package com.labteto.dshmobile.core.wire

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Validates user input without changing the stored zone or interpreting a cron expression locally. */
fun automationTiming(kind: String, value: String, zone: String, weekdays: String): JsonObject = buildJsonObject {
    require(kind in setOf("at", "every", "daily", "weekly", "cron"))
    put("kind", kind)
    when (kind) {
        "at" -> { Instant.parse(value.trim()); put("at", value.trim()) }
        "every" -> { val seconds = value.trim().toLong(); require(seconds >= 60); put("every_seconds", seconds) }
        else -> {
            ZoneId.of(zone.trim())
            put(kind, buildJsonObject {
                put("time_zone", zone.trim())
                if (kind == "cron") {
                    require(value.trim().split(Regex("\\s+")).size == 5)
                    put("expression", value.trim())
                } else {
                    LocalTime.parse(value.trim()); put("time", value.trim())
                    if (kind == "weekly") {
                        val days = weekdays.split(',').map { it.trim().toInt() }
                        require(days.isNotEmpty() && days.distinct().size == days.size && days.all { it in 1..7 })
                        put("weekdays", JsonArray(days.map(::JsonPrimitive)))
                    }
                }
            })
        }
    }
}
