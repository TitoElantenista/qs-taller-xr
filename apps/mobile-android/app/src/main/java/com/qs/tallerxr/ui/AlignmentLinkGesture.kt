package com.qs.tallerxr.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.sqrt

/** What the 3D viewer does with one touch event while a point may be dragged into a line. */
internal enum class LinkTouchResult {
    /** Not a line gesture: taps and the camera handle the event as before. */
    PASS,

    /** The event belongs to the line gesture; the camera must not orbit with it. */
    CONSUME,

    /** The finger left an active line gesture; the camera may close its own gesture, no tap. */
    FINISHED,
}

/**
 * Press on a chosen 3D point, hold still for [holdMillis], then drag.  Until the hold completes
 * the camera keeps priority: a finger that moves beyond [slopPx] earlier is an orbit, and a quick
 * release stays the existing tap that removes the point.
 */
internal class LinkHoldGesture(private val holdMillis: Long, private val slopPx: Float) {
    enum class State { IDLE, PENDING, ACTIVE }

    var state = State.IDLE
        private set
    var origin = 0
        private set
    var position = Offset.Zero
        private set
    private var downPosition = Offset.Zero
    private var downMillis = 0L

    /** A touch starts on the chosen point [origin]; null means the touch started elsewhere. */
    fun down(position: Offset, nowMillis: Long, origin: Int?) {
        cancel()
        if (origin == null) return
        this.origin = origin
        this.position = position
        downPosition = position
        downMillis = nowMillis
        state = State.PENDING
    }

    fun millisUntilActive(nowMillis: Long): Long = (holdMillis - (nowMillis - downMillis)).coerceAtLeast(0L)

    /** Called by the hold timer; true exactly once, when the gesture becomes a line drag. */
    fun activate(nowMillis: Long): Boolean {
        if (state != State.PENDING || millisUntilActive(nowMillis) > 0L) return false
        state = State.ACTIVE
        return true
    }

    fun move(position: Offset): LinkTouchResult = when (state) {
        State.IDLE -> LinkTouchResult.PASS
        State.PENDING -> {
            val dx = position.x - downPosition.x
            val dy = position.y - downPosition.y
            if (dx * dx + dy * dy > slopPx * slopPx) {
                // An orbit that happened to start on a point.  The camera takes over from here.
                state = State.IDLE
                LinkTouchResult.PASS
            } else {
                // Finger jitter during the hold must not turn the model.  Only the hold timer
                // activates the drag, so the viewer always learns about the activation.
                LinkTouchResult.CONSUME
            }
        }
        State.ACTIVE -> {
            this.position = position
            LinkTouchResult.CONSUME
        }
    }

    /** Returns the release point of an active line drag, or null for an ordinary tap/orbit. */
    fun up(position: Offset): Offset? {
        val active = state == State.ACTIVE
        state = State.IDLE
        if (!active) return null
        this.position = position
        return position
    }

    fun cancel() {
        state = State.IDLE
    }
}

/** A chosen point or an offered corner the line would end on. */
internal sealed interface LinkTarget {
    val x: Float
    val y: Float

    data class Point(val label: Int, override val x: Float, override val y: Float) : LinkTarget

    data class Corner(val index: Int, override val x: Float, override val y: Float) : LinkTarget
}

/**
 * Where a line dragged out of [origin] ends: an existing point wins over a free corner at the
 * same spot, and a corner already holding a point never becomes a second point.
 */
internal fun selectLinkTarget(
    origin: Int,
    points: List<AlignmentCandidate>,
    corners: List<AlignmentCandidate>,
    occupiedCorners: Set<Int>,
    x: Float,
    y: Float,
    tolerancePx: Float,
): LinkTarget? {
    selectAlignmentCandidate(points.filter { it.index + 1 != origin }, x, y, tolerancePx)?.let {
        return LinkTarget.Point(it.index + 1, it.x, it.y)
    }
    return selectAlignmentCandidate(corners.filter { it.index !in occupiedCorners }, x, y, tolerancePx)
        ?.let { LinkTarget.Corner(it.index, it.x, it.y) }
}

/** A drawn mark on the camera image or photo, with the label bubble drawn beside it. */
internal data class MarkHandle(val label: Int, val position: Offset, val bubble: Offset?)

/**
 * The mark a touch at [position] starts from, if any.  The operator may grab the ring itself or
 * the number beside it.  The nearest one wins when two marks are close together.
 */
internal fun markUnderFinger(
    marks: List<MarkHandle>,
    position: Offset,
    ringRadiusPx: Float,
    bubbleRadiusPx: Float,
): MarkHandle? = marks
    .mapNotNull { mark ->
        val toRing = distance(mark.position, position)
        val toBubble = mark.bubble?.let { distance(it, position) } ?: Float.POSITIVE_INFINITY
        when {
            toRing <= ringRadiusPx -> mark to toRing
            toBubble <= bubbleRadiusPx -> mark to toBubble
            else -> null
        }
    }
    .minByOrNull { it.second }
    ?.first

/**
 * A mark placed while pressing on another mark only gets a line when the finger left the start
 * mark: releasing on the spot keeps the former behaviour of a plain mark there.
 */
internal fun linkedMarkOrigin(start: MarkHandle?, releasePosition: Offset, startRadiusPx: Float): Int? =
    start?.takeIf { distance(it.position, releasePosition) > startRadiusPx }?.label

/** Maps a viewport point into a magnifier canvas whose centre shows [cursor] enlarged [factor] times. */
internal fun lensPoint(point: Offset, cursor: Offset, lensCentre: Offset, factor: Float): Offset =
    lensCentre + (point - cursor) * factor

/**
 * The part of the segment from [start] to [end] outside [startGapPx] and [endGapPx], so a line
 * never covers the exact spot inside a mark ring or crosshair.  Null when nothing remains.
 */
internal fun trimmedSegment(start: Offset, end: Offset, startGapPx: Float, endGapPx: Float): Pair<Offset, Offset>? {
    val length = distance(start, end)
    if (length <= startGapPx + endGapPx + 1f) return null
    val direction = (end - start) / length
    return (start + direction * startGapPx) to (end - direction * endGapPx)
}

private fun distance(first: Offset, second: Offset): Float {
    val dx = first.x - second.x
    val dy = first.y - second.y
    return sqrt(dx * dx + dy * dy)
}
