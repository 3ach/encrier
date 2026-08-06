package com.zachzundel.encrier

// Spec §9 — single tunables file. Gesture thresholds get calibrated from
// ElbowDebug logs after a week of real use.
object Tunables {
    const val LINE_HEIGHT_DP = 72f        // calibrate to owner's handwriting
    const val IDLE_COMMIT_MS = 2000L
    // Calibrated 2026-08-06 from ElbowDebug logs of Zach's real elbows:
    // drops past rule 37-52px (lh=90px), runs 44-56px, turns 84-112°.
    const val GESTURE_MIN_DROP_FRAC = 0.35f // × lineHeight past anchor's bottom rule
    const val GESTURE_MIN_RUN_DP = 36f      // also ≥ RUN_VS_DROP × segment A's drop
    const val GESTURE_RUN_VS_DROP = 0.75f
    const val GESTURE_TURN_MIN_DEG = 60f
    const val GESTURE_TURN_MAX_DEG = 120f
    const val STROKE_BBOX_PAD_DP = 4f
    const val GESTURE_DEBUG = true         // log strokes that start on committed ink but fail detection

    // Stylus must dwell over one row this long before its ink is revealed —
    // a pen approaching to write passes through hover far faster than this.
    const val HOVER_REVEAL_MS = 600L

    // Strike-out (→ DONE) and scribble-out (→ DELETE) over a committed row's text.
    const val STRIKE_MIN_COVER = 0.6f       // horizontal overlap with text span, fraction
    const val STRIKE_MAX_HEIGHT_FRAC = 0.4f // × lineHeight max vertical extent
    const val SCRIBBLE_MIN_COVER = 0.4f
    const val SCRIBBLE_MIN_REVERSALS = 4    // horizontal direction changes
}
