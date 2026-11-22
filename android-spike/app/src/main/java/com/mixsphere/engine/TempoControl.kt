/*
 * Mixsphere Phase 0 spike - validated tempo control range.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.mixsphere.engine

internal object TempoControl {
    const val MIN_RATE = 0.05
    const val MAX_RATE = 4.0

    fun normalize(rate: Double): Double {
        require(rate.isFinite()) { "tempo must be finite" }
        return rate.coerceIn(MIN_RATE, MAX_RATE)
    }
}
