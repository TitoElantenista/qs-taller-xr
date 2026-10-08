package com.qs.tallerxr.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Horseshoe magnet for the experimental «Snap».  Material Icons has none; this follows their
 * 24 dp grid and filled style: a U-shaped body with two separate pole shoes on top.
 */
internal val MagnetIcon: ImageVector by lazy {
    materialIcon(name = "QsMagnet") {
        materialPath {
            // Body: outer arc radius 8, inner arc radius 3, both centred at (12, 13).
            moveTo(4f, 8f)
            lineTo(4f, 13f)
            arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 20f, y1 = 13f)
            lineTo(20f, 8f)
            lineTo(15f, 8f)
            lineTo(15f, 13f)
            arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 9f, y1 = 13f)
            lineTo(9f, 8f)
            close()
            // Pole shoes, separated from the body by a gap.
            moveTo(4f, 3f)
            lineTo(9f, 3f)
            lineTo(9f, 6.5f)
            lineTo(4f, 6.5f)
            close()
            moveTo(15f, 3f)
            lineTo(20f, 3f)
            lineTo(20f, 6.5f)
            lineTo(15f, 6.5f)
            close()
        }
    }
}
