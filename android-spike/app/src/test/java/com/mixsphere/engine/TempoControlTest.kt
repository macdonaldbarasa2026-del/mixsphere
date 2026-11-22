/*
 * Mixsphere Phase 0 - tempo boundary unit tests.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.mixsphere.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TempoControlTest {
    @Test
    fun preservesTempoInsideSupportedRange() {
        assertEquals(1.0, TempoControl.normalize(1.0), 0.0)
        assertEquals(1.1, TempoControl.normalize(1.1), 0.0)
    }

    @Test
    fun clampsTempoToNativeEngineRange() {
        assertEquals(TempoControl.MIN_RATE, TempoControl.normalize(-2.0), 0.0)
        assertEquals(TempoControl.MAX_RATE, TempoControl.normalize(8.0), 0.0)
    }

    @Test
    fun rejectsNonFiniteTempo() {
        assertThrows(IllegalArgumentException::class.java) {
            TempoControl.normalize(Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TempoControl.normalize(Double.POSITIVE_INFINITY)
        }
    }
}
