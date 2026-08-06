package com.zachzundel.encrier

// Spec §9 — single tunables file. Gesture thresholds get calibrated from
// ElbowDebug logs after a week of real use.
object Tunables {
    const val LINE_HEIGHT_DP = 72f        // calibrate to owner's handwriting
    const val IDLE_COMMIT_MS = 2000L
    const val GESTURE_MIN_DROP_FRAC = 0.5f // × lineHeight past anchor's bottom rule
    const val GESTURE_MIN_RUN_DP = 76f     // ≈2cm; also ≥1.0× segment A's drop
    const val GESTURE_TURN_MIN_DEG = 60f
    const val GESTURE_TURN_MAX_DEG = 120f
    const val STROKE_BBOX_PAD_DP = 4f
    const val GESTURE_DEBUG = true         // log strokes that start on committed ink but fail detection
}
