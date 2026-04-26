package com.timetwister.core

import java.time.ZoneId

/** A time reference we detected in user-typed text. */
data class DetectedTime(
    val hour: Int,          // 0..23
    val minute: Int,        // 0..59
    val zone: ZoneId,       // defaults to the system zone when none was written
    val hadExplicitZone: Boolean,
    val range: IntRange,    // range in the original string
    val originalText: String,
)
