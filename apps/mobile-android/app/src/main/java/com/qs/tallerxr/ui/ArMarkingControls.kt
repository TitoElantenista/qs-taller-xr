package com.qs.tallerxr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.qs.tallerxr.ar.AlignmentMarking
import com.qs.tallerxr.ar.CapturedImageFit
import com.qs.tallerxr.ar.CorrespondenceSolution

/** How a point chip looks: its state in the round, or its deviation once a fit exists. */
internal enum class ChipTone { CURRENT, MARKED, SKIPPED, PENDING, GOOD, DOUBTFUL, BAD, MISSING }

internal fun chipTone(state: AlignmentMarking.PointState): ChipTone = when (state) {
    AlignmentMarking.PointState.CURRENT -> ChipTone.CURRENT
    AlignmentMarking.PointState.MARKED -> ChipTone.MARKED
    AlignmentMarking.PointState.SKIPPED -> ChipTone.SKIPPED
    AlignmentMarking.PointState.PENDING -> ChipTone.PENDING
}

/**
 * Deviation of [label]'s mark in [station] from the fitted CAD: green up to half the tolerance,
 * orange up to the tolerance, red beyond.  The same limits colour the marks on the photo.
 */
internal fun residualTone(solution: CorrespondenceSolution?, label: Int, station: Int): ChipTone {
    val residual = solution?.residuals
        ?.filter { it.label == label && it.station == station }
        ?.maxOfOrNull { it.lateralMm } ?: return ChipTone.MISSING
    return when {
        residual <= solution.toleranceMm / 2f -> ChipTone.GOOD
        residual <= solution.toleranceMm -> ChipTone.DOUBTFUL
        else -> ChipTone.BAD
    }
}

internal fun toneColor(tone: ChipTone): Color = when (tone) {
    ChipTone.CURRENT -> Color(0xFFFFB703)
    ChipTone.MARKED, ChipTone.GOOD -> Color(0xFF5AD67D)
    ChipTone.DOUBTFUL -> Color(0xFFFFB703)
    ChipTone.BAD -> Color(0xFFFF7A6E)
    ChipTone.SKIPPED, ChipTone.PENDING, ChipTone.MISSING -> Color(0xFF9AA7B0)
}

/**
 * P1…Pn of the round.  Tapping one marks it next: out of order, or again to correct it; its old
 * mark stays until the new one is placed.  Tapping the highlighted one returns to the order.
 */
@Composable
internal fun PointChips(
    labels: List<Int>,
    tone: (Int) -> ChipTone,
    describe: (Int) -> String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEach { label ->
            val chip = tone(label)
            val colour = toneColor(chip)
            val filled = chip == ChipTone.CURRENT
            Surface(
                onClick = { onSelect(label) },
                shape = RoundedCornerShape(16.dp),
                color = if (filled) colour else Color.Transparent,
                border = BorderStroke(if (filled) 0.dp else 1.5.dp, colour),
                modifier = Modifier.height(32.dp).semantics { contentDescription = describe(label) },
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        "P$label",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (filled) Color(0xFF071019) else colour,
                        textDecoration = if (chip == ChipTone.SKIPPED) TextDecoration.LineThrough else null,
                    )
                    when (chip) {
                        ChipTone.MARKED, ChipTone.GOOD ->
                            Icon(Icons.Default.Check, contentDescription = null, tint = colour, modifier = Modifier.size(16.dp))
                        ChipTone.SKIPPED ->
                            Icon(Icons.Default.Remove, contentDescription = null, tint = colour, modifier = Modifier.size(16.dp))
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** A mark and where the fitted CAD puts the same landmark, both in captured photo pixels. */
internal data class ResidualVector(
    val markX: Float,
    val markY: Float,
    val cadX: Float,
    val cadY: Float,
    val tone: ChipTone,
)

/**
 * The fitted CAD on the frozen photo: its visible edges thin and translucent, and for each mark a
 * short line to where the fit puts its landmark.  Hidden while a finger is on the photo, so it
 * never covers a point or a line being placed.
 */
@Composable
internal fun PhotoCheckOverlay(edges: FloatArray?, residuals: List<ResidualVector>, fit: CapturedImageFit) {
    Canvas(Modifier.fillMaxSize()) {
        edges?.let { segments ->
            val stroke = 1.5.dp.toPx()
            var index = 0
            while (index + 3 < segments.size) {
                val start = fit.viewportPoint(segments[index], segments[index + 1])
                val end = fit.viewportPoint(segments[index + 2], segments[index + 3])
                drawLine(Color(0x9E4CC9F0), Offset(start[0], start[1]), Offset(end[0], end[1]), stroke, StrokeCap.Round)
                index += 4
            }
        }
        residuals.forEach { vector ->
            val mark = fit.viewportPoint(vector.markX, vector.markY)
            val cad = fit.viewportPoint(vector.cadX, vector.cadY)
            val colour = toneColor(vector.tone)
            drawLine(Color(0xD0071019), Offset(mark[0], mark[1]), Offset(cad[0], cad[1]), 4.dp.toPx(), StrokeCap.Round)
            drawLine(colour, Offset(mark[0], mark[1]), Offset(cad[0], cad[1]), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(Color(0xD0071019), 4.dp.toPx(), Offset(cad[0], cad[1]))
            drawCircle(colour, 2.5.dp.toPx(), Offset(cad[0], cad[1]))
        }
    }
}

/** Spoken and long-press description of a point chip. */
internal fun pointChipDescription(marking: AlignmentMarking, label: Int, solution: CorrespondenceSolution? = null): String {
    val state = when (marking.stateOf(label)) {
        AlignmentMarking.PointState.CURRENT ->
            if (marking.isMarkedInStation(label)) "wird neu gesetzt" else "als Nächstes zu markieren"
        AlignmentMarking.PointState.MARKED -> "markiert"
        AlignmentMarking.PointState.SKIPPED -> "übersprungen"
        AlignmentMarking.PointState.PENDING -> "noch offen"
    }
    val deviation = solution?.residuals
        ?.filter { it.label == label && it.station == marking.station }
        ?.maxOfOrNull { it.lateralMm }
        ?.let { ", Abweichung ${it.toInt()} mm" }.orEmpty()
    return "P$label $state$deviation. Antippen, um ihn jetzt zu markieren oder neu zu setzen."
}
