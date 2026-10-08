package com.qs.tallerxr.ar

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val arPhotoTimestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
private val drawingAndPositionPattern = Regex("^\\s*(\\d{6})\\s*[./]\\s*(\\d+)")

internal fun arPhotoPositionLabel(positionNumber: String): String {
    val match = drawingAndPositionPattern.find(positionNumber)
    return if (match != null) {
        "${match.groupValues[1]}.${match.groupValues[2]}"
    } else {
        positionNumber.trim().ifBlank { "BAUTEIL" }
    }
}

internal fun arPhotoDisplayName(positionNumber: String, timestamp: LocalDateTime): String {
    val safePosition = arPhotoPositionLabel(positionNumber)
        .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
        .trim('_')
        .ifBlank { "BAUTEIL" }
    return "${safePosition}_${arPhotoTimestampFormat.format(timestamp)}.jpg"
}
