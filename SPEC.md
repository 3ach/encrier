# Encrier — Implementation Spec

Stylus-only handwritten task capture for Android (Daylight DC-1). One infinite
ruled tape; each written line becomes a task item via on-device recognition.
No cloud, no keyboard, no pages.

## 0. Validation gates (do these before building anything)

1. **ML Kit smoke test on the DC-1.** Minimal activity: download the en-US
   Digital Ink model, capture strokes, recognize a line. If model download or
   recognition fails on this device, stop and report — the whole design hangs
   on it. https://developers.google.com/ml-kit/vision/digital-ink-recognition/android
2. **Stroke fidelity check.** Compose pointer events may undersample EMR stylus
   input. If captured ink looks chunky, switch capture to `MotionEvent` with
   historical points (`getHistoricalX/Y/EventTime`) behind the Compose surface.
   https://developer.android.com/develop/ui/compose/touch-input/stylus-input

## 1. Constraints

- Android, Kotlin, Jetpack Compose, Room. Single module. minSdk 26.
- All data local (app-private SQLite). No analytics, no crash reporting, no
  network use except ML Kit model download/update. Keep `INTERNET` permission.
- **No keyboard input anywhere in the app.** All text is derived from ink.
  All corrections are tap or ink interactions.
- Grayscale, high-contrast, hard-edged UI (e-paper display). No shadows,
  gradients, or animation easing. Selected states use inverted fill.
  Timestamps in a mono face. (See accompanying HTML mockup for reference.)

## 2. Data model (mutable columns — deliberately not event-sourced)

```
lines(
  id INTEGER PK,
  seq REAL NOT NULL,          -- fractional ordering; insert = midpoint
  createdAt INTEGER NOT NULL
)

strokes(
  id INTEGER PK,
  lineId INTEGER NOT NULL REFERENCES lines,
  ord INTEGER NOT NULL,       -- stroke order within line
  pointsJson TEXT NOT NULL,   -- [[x,y,t],...] in LINE-RELATIVE coordinates
  addedAt INTEGER NOT NULL
)

items(
  id INTEGER PK,
  lineId INTEGER UNIQUE NOT NULL REFERENCES lines,
  text TEXT NOT NULL,
  candidatesJson TEXT NOT NULL, -- full ranked list from last recognition
  parentId INTEGER NULL REFERENCES items,
  status TEXT NOT NULL,         -- OPEN | DONE | DROPPED
  createdAt INTEGER NOT NULL,   -- first commit time; never changes
  completedAt INTEGER NULL,
  droppedAt INTEGER NULL
)
```

Rules:
- Ink is insert-only: strokes are never mutated or deleted (exception: empty
  spawned child lines with zero strokes are garbage-collected on idle).
- `items.text` is last-writer-wins between re-recognition and candidate
  selection. No pinning. Deliberate.
- Strokes stored line-relative; render y = f(line seq order) × lineHeight.
  Lazy-load strokes by visible line range.

## 3. Tape (Write screen)

- Single vertically scrolling surface of ruled lines (`lineHeight` default
  72dp, a calibration constant). No page boundaries.
- Opens scrolled to the last occupied line; persistent "↓ Latest" button.
- Input routing: `pointerType == STYLUS` draws; touch scrolls. No stylus-only
  toggle needed in v1 (device has EMR pen); add later if emulator testing
  demands it.
- Writing is bottom-append: new strokes below the last occupied line create
  new lines. Strokes whose y-centroid falls on an existing line **amend** it
  (append strokes, trigger re-recognition of that line).
- Day markers: render a faint gutter label (e.g. `JUL 30 — THU`) above the
  first line whose earliest stroke timestamp crosses a local-date boundary.
  Derived at render time from data; not stored.
- Item state overlays on tape ink: DONE lines render struck through; DROPPED
  lines render faded. Ink itself untouched.

## 4. Recognition pipeline

- Engine: ML Kit Digital Ink Recognition, `en-US` model, downloaded on first
  launch with a blocking progress state; app is unusable for conversion until
  present. Handle re-download after data clear.
- Trigger: 2000ms (`IDLE_COMMIT_MS`) after the last `pointerup`, recognize
  every line that has unrecognized or amended strokes. Recognition is
  per-line: one `Ink` per line, `RecognitionContext` with `WritingArea(width,
  lineHeight)`. Convert line-relative coords directly.
- Commit: non-blank recognition on a line without an item → insert item
  (`createdAt = now`). Recognition on a line WITH an item → update `text` and
  `candidatesJson` in place. Blank/failed recognition → no item; ink retained.
- Display: while a line has uncommitted ink, show a dashed provisional chip
  under it; on commit, solid chip with recognized text. Recognized text is
  always visible under its ink on the tape.
- Store the complete ranked candidate list on every recognition.

## 5. Child gesture ("elbow")

Single-stroke gesture: start on an existing item's ink, draw down past the
next rule, turn ~90° right. Spawns an empty child line for writing.

Detection — run on every stroke end, BEFORE handwriting routing:
1. Stroke start point lies within the union bbox (padded ~4dp) of a
   **committed** line's strokes. Strokes starting on the pending
   (actively-written, uncommitted) line are never gestures.
2. Fit the stroke as two dominant segments (e.g. split at max-curvature
   point). Segment A: predominantly downward; its endpoint (turn point) must
   land ≥ 0.5 × lineHeight past the anchor line's bottom rule.
3. Segment B: predominantly rightward; length ≥ max(GESTURE_MIN_RUN_DP,
   1.0 × segment A's vertical drop). Turn angle between segments in 60–120°.

On match:
- Exclude the stroke from ink storage and recognition entirely.
- Insert a new line with `seq` = midpoint(anchor.seq, next.seq); shiftless.
- Render a crisp connector glyph (└─) in place of the hand-drawn stroke.
- Item created on that line (via the normal §4 commit path) gets
  `parentId = anchor line's item`.
- If the spawned line receives no strokes before the next idle commit, delete
  the line and connector (GC).

On non-match: stroke routes to normal handwriting handling for whatever line
its centroid falls on (this is how amendment of old lines works).

Tuning support (required in v1): a debug setting that logs, for every stroke
that started on committed ink but failed gesture detection, its computed
drop-depth, turn angle, and rightward run — so thresholds can be set from
real handwriting after a week of use. Known risk: deep lowercase-q descenders
during amendments; the depth (≥0.5 line past rule) and run-length gates are
the defense. If real-world separation proves narrow, fallback design is a
~300ms pen-dwell required at gesture start (do not build in v1).

## 6. Items screen

- Tree list: roots by `createdAt` asc, children indented beneath parents with
  a connector, orphaned children (parent DONE/DROPPED) still shown.
- Row: item text (primary), mono meta line (`added Jul 30`, child completion
  ratio on parents). DONE rows struck through and inert.
- Actions per row: `Done` (sets completedAt, status), `Drop` (sets droppedAt,
  status). Stylus-tappable button sizes.
- Tap row text → inline panel: rendered source ink (drawn from stored
  strokes, not a font), label with the line's date, then the ranked
  candidates as buttons — current text selected/inverted. Tapping a candidate
  sets `items.text`, closes panel. Footer note directs to ink amendment on
  the tape when no candidate is right. **No text field.**

## 7. Reports screen

- Segmented preset: 7 / 30 / 90 days.
- Counts: added (`createdAt` in range), completed (`completedAt` in range),
  dropped (`droppedAt` in range).
- Two lists below: Completed (date + text, desc), Added (date + text, desc).

## 8. Explicit non-goals for v1

No staleness detection or notifications. No cloud/API recognition (Claude).
No sync. No keyboard. No photo/camera OCR. No pages. No indent-based
hierarchy inference. No drag-to-reparent. No text pinning. No multi-language
models. No export (schema should not preclude a later SQLite-file export).

## 9. Constants (single tunables file)

```
LINE_HEIGHT_DP        = 72      // calibrate to owner's handwriting
IDLE_COMMIT_MS        = 2000
GESTURE_MIN_DROP      = 0.5 * lineHeight past anchor's bottom rule
GESTURE_MIN_RUN_DP    = 76      // ≈2cm; and ≥1.0× vertical drop
GESTURE_TURN_RANGE    = 60°..120°
STROKE_BBOX_PAD_DP    = 4
```

## 10. Acceptance checks

1. Airplane mode (after model download): write 3 lines → 3 items appear
   within ~2s of pen-up, correct text, correct `createdAt` ordering.
2. Amend a committed line (add a word) → its item's text updates in place;
   `createdAt` unchanged; no duplicate item.
3. Elbow gesture from a committed item → connector renders, empty line
   appears under it; writing there → child item with correct `parentId`;
   drawing the gesture and writing nothing → line disappears on idle.
4. Write a word containing a deep descender on the pending line → never
   triggers the gesture.
5. Tap an item, select a different candidate → row text updates; reopening
   shows that candidate selected; ink unchanged.
6. Mark items done/dropped → tape ink renders struck/faded; Reports counts
   match for each preset window.
7. Kill and relaunch with 500+ lines → tape opens at latest line quickly
   (lazy load working); scroll to top shows day markers at date boundaries.
8. Nothing in the app accepts keyboard focus.

## 11. Post-v1 revisions (as built, Aug 2026)

Validation gates: both passed on the DC-1. Stroke capture uses Compose raw
pointer events plus `change.historical` (~550Hz effective, max gap ~4ms); no
MotionEvent fallback needed. `detectDragGestures` must not be used (touch slop
eats stroke starts).

Design changes from v1, in the order they were decided:

- **Items screen removed.** The tape is the single task surface; tabs are
  WRITE | REPORTS.
- **Committed lines collapse to text.** Ink is the input method, not the
  display. A committed row renders its recognized text (right-aligned mono
  date, child ratio on parents); the ink is stored but hidden.
- **Inline panel** replaces the Items row actions: tap a committed row
  (finger tap, or a tap-length pen stroke) → source ink preview, ranked
  candidates, DONE / DROP / REOPEN / DELETE / CLOSE. Panel state is derived
  live from the DB, never a snapshot.
- **Delete exists** and removes item + line + ink; the tape closes the gap.
  (Supersedes v1's ink-is-insert-only rule.)
- **Strike-out / scribble-out.** A horizontal stroke covering the text of a
  committed row marks it DONE; a zigzag scribble over it deletes it. Neither
  stores ink. Thresholds in Tunables.
- **Hover reveal.** Dwelling the pen ≥600ms over a committed row swaps it to
  its source ink for positional amendment; moving away restores text. A pen
  descending to write never trips it, and the row's display mode is latched
  from first amendment stroke until commit.
- **Amendment is append-only in text mode.** Strokes written after a row's
  text stay visible where written until commit, but are stored x-shifted past
  the original ink's right edge (word gap) so the concatenated ink reads
  linearly. Mid-line insertion in text mode is an explicit non-goal — use
  hover reveal for insertions.
- **Typed corrections.** The panel has a keyboard field: type the
  intended text, APPLY updates the item and stores a `corrections` row (ink
  snapshot + candidates + corrected text) as recognizer training data. Picking
  a ranked candidate does not record a correction.
- **Gesture anchors are row slot bands**, not ink bboxes (ink is hidden).
- **Elbow thresholds** recalibrated from real handwriting: drop ≥ 0.35 ×
  lineHeight, run ≥ 36dp and ≥ 0.75 × drop. Reliability still imperfect;
  open item.
- **Multiple tapes.** Lines belong to a tape; the current tape is persisted
  across launches and shown as the top-bar title (the id-1 default tape
  displays as "encrier"). Tapping the title on the tape view opens a picker
  card: switch tapes, or type a name to create one (keyboard input, like
  typed corrections). History reports the current tape only.
- **Editable item date.** The panel's "written aug 6" caption carries two
  quiet ◂ ▸ nudges that shift `item.createdAt` by ±1 day (panel stays open
  and refreshes live). The row's meta date and History follow; the tape's
  day-marker gutters derive from stroke timestamps and deliberately don't.
- **Jump to date.** A calendar button on the tape's top bar (left of the
  clock) opens a notebook card listing the tape's day markers, most recent
  first; tapping one scrolls that day's first line to the top of the viewport.

Device notes: DC-1 ships `persist.log.tag=I` (Log.d is dropped device-wide);
its digitizer reports ~550Hz. Do not vary only draw-time attributes
(color/decoration) between TextMeasurer measure calls — the layout cache
ignores them but replays them.

Architecture: root package holds app plumbing (`Graph`, `TapeSession`,
`Tunables`); `data/` owns Room (entities, dao, migrations) plus pure point and
date helpers; `gesture/` and `ink/` are pure/model layers; `ui/` splits the
tape into `TapeScreen` (state + input), `TapeCanvas`, `ItemPanel`,
`AppChrome`, with shared `InkDraw`/`StylusInput`/`Theme` primitives.

## 12. Sketch pages (added Aug 2026)

Free-drawing notebook pages alongside the task tapes: stylus draws raw ink
(no recognition), finger scrolls, the barrel button erases whole strokes.
Each page has a name and a background — blank, dot grid, or line grid —
switchable from the top bar; pages are created/switched from a picker on the
page title, mirroring tapes. Ink lives in page coordinates in `page_strokes`
(schema v3); the current page persists across launches.
