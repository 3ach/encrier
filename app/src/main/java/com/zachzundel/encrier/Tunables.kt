package com.zachzundel.encrier

// Spec §9 — single tunables file.
object Tunables {
    const val LINE_HEIGHT_DP = 72f        // writing height; calibrate to owner's handwriting
    const val TEXT_ROW_DP = 34f           // tight height for committed (typed) rows
    const val DAY_MARKER_INSET_DP = 14f   // extra height on tight rows carrying a day marker
    const val IDLE_COMMIT_MS = 2000L
    const val GESTURE_DEBUG = true // log strike/scribble rejections for tuning

    // Interaction geometry.
    const val TOUCH_TAP_SLOP_DP = 12f      // touch movement beyond this is a scroll, not a tap
    const val TAP_MAX_LEN_DP = 10f         // stylus stroke at most this long opens the panel
    const val ERASE_RADIUS_DP = 10f        // stroke-eraser contact radius
    const val OVERLAY_LINGER_MS = 400L     // stored ink stays overlaid until Room re-emits it

    // Stylus must dwell over one row this long before its ink is revealed —
    // a pen approaching to write passes through hover far faster than this.
    const val HOVER_REVEAL_MS = 600L

    // Word-sized gap inserted between existing ink and strokes appended while a
    // row is displayed as text (pen position is relative to the short text, not
    // the original ink — stored strokes are shifted past the ink's right edge).
    const val AMEND_GAP_DP = 24f

    // Sketch pages: dot/line grid pitch.
    const val SKETCH_GRID_DP = 28f

    // Strike-out (→ DONE) and scribble-out (→ DELETE) over a committed row's text.
    const val STRIKE_MIN_COVER = 0.6f       // horizontal overlap with text span, fraction
    const val STRIKE_MAX_HEIGHT_FRAC = 0.4f // × lineHeight max vertical extent
    const val SCRIBBLE_MIN_COVER = 0.4f
    const val SCRIBBLE_MIN_REVERSALS = 4    // horizontal direction changes
}
