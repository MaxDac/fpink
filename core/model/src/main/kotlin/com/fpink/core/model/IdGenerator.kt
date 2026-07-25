package com.fpink.core.model

import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

object IdGenerator {
    private val chars = ('a'..'z') + ('0'..'9')

    fun generate(clock: Clock = Clock.System): String {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val datePart = buildString {
            append(now.year.toString().padStart(4, '0'))
            append(now.monthNumber.toString().padStart(2, '0'))
            append(now.dayOfMonth.toString().padStart(2, '0'))
            append(now.hour.toString().padStart(2, '0'))
            append(now.minute.toString().padStart(2, '0'))
            append(now.second.toString().padStart(2, '0'))
        }
        val randomPart = buildString {
            repeat(4) { append(chars.random()) }
        }
        return datePart + randomPart
    }
}
