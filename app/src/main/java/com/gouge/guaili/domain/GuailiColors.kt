package com.gouge.guaili.domain

import kotlin.math.max
import kotlin.math.roundToInt

fun guailiColorStrength(value: Int?, maxAbs: Int = 20): Float {
    if (value == null) return 0.0f
    val divisor = max(1, maxAbs).toFloat()
    return (value / divisor).coerceIn(-1.0f, 1.0f)
}

fun guailiBackgroundArgb(value: Int?): Long {
    if (value == null) return 0xFF11161C
    val strength = guailiColorStrength(value)
    return when {
        strength < 0f -> blendArgb(0xFF31363D, 0xFFBE0041, -strength)
        strength > 0f -> blendArgb(0xFF31363D, 0xFF007A1A, strength)
        else -> 0xFF31363D
    }
}

private fun blendArgb(from: Long, to: Long, amount: Float): Long {
    val fraction = amount.coerceIn(0f, 1f)
    fun channel(shift: Int): Long {
        val start = ((from shr shift) and 0xFF).toFloat()
        val end = ((to shr shift) and 0xFF).toFloat()
        return (start + (end - start) * fraction).roundToInt().toLong()
    }
    return (0xFFL shl 24) or
        (channel(16) shl 16) or
        (channel(8) shl 8) or
        channel(0)
}
