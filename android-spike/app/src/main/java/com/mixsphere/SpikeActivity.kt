/*
 * Mixsphere Phase 0 spike - minimal host activity.
 * Copyright (C) 2026 Mixsphere contributors. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Compose UI is Phase 2. This activity exists only to host the native engine
 * and expose its status for the proof-of-life instrumentation test.
 */
package com.mixsphere

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.mixsphere.engine.MixsphereEngine

class SpikeActivity : Activity() {
    private var engine: MixsphereEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this)
        view.text = "Mixsphere Phase 0 engine spike"
        setContentView(view)
        engine = MixsphereEngine()
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        super.onDestroy()
    }
}
