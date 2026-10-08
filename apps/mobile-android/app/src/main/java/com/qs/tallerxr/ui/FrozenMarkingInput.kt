package com.qs.tallerxr.ui

import androidx.compose.ui.geometry.Offset
import com.qs.tallerxr.ar.CapturedAlignmentView
import com.qs.tallerxr.ar.CapturedImageFit
import com.qs.tallerxr.ar.CorrespondenceSighting

/** Where the finger was lifted, and the mark of this round it was dragged out of, if any. */
internal data class MarkRelease(val position: Offset, val linkedFrom: Int? = null)

/** The exact same fit draws the image, samples the magnifier and converts its touch to a ray. */
internal fun frozenMarkCommitter(
    geometry: CapturedAlignmentView,
    fit: CapturedImageFit,
    label: Int,
    station: Int,
    onMarked: (CorrespondenceSighting, Int?) -> Unit,
): (MarkRelease) -> Unit {
    // Do not return ::localFunction here. Kotlin considers those references equal even when
    // their captured fit changes, so rememberUpdatedState can retain the pre-layout callback.
    // A lambda has identity equality and lets Compose publish the current photo mapping.
    return commit@{ release ->
        val point = fit.imagePoint(release.position.x, release.position.y) ?: return@commit
        geometry.sighting(label, station, point[0], point[1])?.let { onMarked(it, release.linkedFrom) }
    }
}
