package com.qs.tallerxr.ar

import com.google.ar.core.Pose
import kotlin.math.min

/**
 * Frame bookkeeping of the operator alignment.
 *
 * ARCore's world frame is ephemeral: its numbers may shift whenever the map is refined, and the
 * operator walks between marking stations for half a minute.  Every mark is therefore stored
 * relative to one reference anchor created next to the part, the fit is solved in that frame, and
 * the model is posed each frame as `worldFromReference · referenceFromModel`, exactly as ADR 0001
 * requires for any pose that outlives a frame.
 */
object AlignmentFrames {
    /** Expresses a world ray in the frame of [worldFromReference], taken from the same frame. */
    fun rayInReference(worldFromReference: Pose, worldRay: WorldRay): WorldRay {
        val referenceFromWorld = worldFromReference.inverse()
        return WorldRay(
            origin = referenceFromWorld.transformPoint(worldRay.origin),
            direction = referenceFromWorld.rotateVector(worldRay.direction),
        )
    }

    fun rayInWorld(worldFromReference: Pose, referenceRay: WorldRay): WorldRay = WorldRay(
        origin = worldFromReference.transformPoint(referenceRay.origin),
        direction = worldFromReference.rotateVector(referenceRay.direction),
    )

    /** A world orientation expressed in the reference frame. */
    fun quaternionInReference(worldFromReference: Pose, worldQuaternionXyzw: FloatArray): FloatArray =
        worldFromReference.inverse()
            .compose(Pose.makeRotation(worldQuaternionXyzw))
            .rotationQuaternion

    fun worldFromModel(worldFromReference: Pose, solution: CorrespondenceSolution): Pose =
        worldFromReference.compose(Pose(solution.translation, solution.quaternionXyzw))

    /**
     * Pose for the final anchor: the model pose moved to the centre of the part.  ARCore keeps an
     * anchor most stable around its own position, and the assembly origin of a long beam may sit
     * metres away from the steel.
     */
    fun anchorPoseAtModelCentre(worldFromModel: Pose, modelCentre: FloatArray): Pose =
        worldFromModel.compose(Pose.makeTranslation(modelCentre))

    /** Inverse of [anchorPoseAtModelCentre] for the pose the anchor reports in a later frame. */
    fun worldFromModelAtAnchor(worldFromAnchor: Pose, modelCentre: FloatArray): Pose =
        worldFromAnchor.compose(
            Pose.makeTranslation(-modelCentre[0], -modelCentre[1], -modelCentre[2]),
        )

    data class Legend(val worldFromModel: Pose, val scale: Float)

    /**
     * Miniature of the CAD pinned to the top right of the camera view while any station is
     * being marked. It keeps the real part clear even when a previous fit exists. It is oriented
     * exactly as the 3D viewer showed the model when the landmarks were chosen, so P1..Pn remain
     * recognisable throughout the rounds. The returned pose
     * applies to the model scaled by [Legend.scale] around its own origin.
     */
    fun legend(
        displayCameraPose: Pose,
        projection: FloatArray,
        viewerCameraQuaternionXyzw: FloatArray,
        modelCentre: FloatArray,
        modelDiameterM: Float,
        distanceM: Float = 0.6f,
    ): Legend? {
        if (projection.size < 16 || !(modelDiameterM > 0f)) return null
        val halfWidth = distanceM / projection[0]
        val halfHeight = distanceM / projection[5]
        if (!halfWidth.isFinite() || !halfHeight.isFinite() || halfWidth <= 0f || halfHeight <= 0f) {
            return null
        }
        val size = 0.8f * min(halfWidth, halfHeight)
        val scale = size / modelDiameterM
        val rotation = AlignmentSeed.modelRotationFromViewerCamera(
            arCameraQuaternionXyzw = displayCameraPose.rotationQuaternion,
            viewerCameraQuaternionXyzw = viewerCameraQuaternionXyzw,
        )
        val centreWorld = displayCameraPose.transformPoint(
            floatArrayOf(halfWidth - 0.62f * size, halfHeight - 0.78f * size, -distanceM),
        )
        val scaledCentre = Pose.makeRotation(rotation).rotateVector(
            FloatArray(3) { modelCentre[it] * scale },
        )
        return Legend(
            worldFromModel = Pose(
                FloatArray(3) { centreWorld[it] - scaledCentre[it] },
                rotation,
            ),
            scale = scale,
        )
    }

    /** World position of a CAD point under a pose whose model is scaled around its origin. */
    fun posedPoint(worldFromModel: Pose, scale: Float, modelPoint: FloatArray): FloatArray =
        worldFromModel.transformPoint(FloatArray(3) { modelPoint[it] * scale })
}
