package com.gouge.guaili.domain

import kotlin.math.max

fun guailiColorStrength(value: Int?, maxAbs: Int = 20): Float {
    if (value == null) return 0.0f
    val divisor = max(1, maxAbs).toFloat()
    return (value / divisor).coerceIn(-1.0f, 1.0f)
}
