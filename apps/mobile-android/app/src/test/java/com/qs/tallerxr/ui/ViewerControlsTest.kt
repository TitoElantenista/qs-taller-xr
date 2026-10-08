package com.qs.tallerxr.ui

import com.qs.tallerxr.ar.CorrespondenceQuality
import com.qs.tallerxr.data.Bounds
import com.qs.tallerxr.data.Part
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerControlsTest {
    @Test
    fun provisionalSingleViewCanBeAnchoredWhenTheOperatorChoosesIt() {
        assertTrue(canAnchorAlignment(CorrespondenceQuality.DOUBTFUL, true, true, true, allowProvisional = true))
        assertFalse(canAnchorAlignment(CorrespondenceQuality.DOUBTFUL, true, true, true, allowProvisional = false))
        assertTrue(canAnchorAlignment(CorrespondenceQuality.ACCEPTABLE, true, true, true, allowProvisional = false))
    }

    @Test
    fun optionalAnchoringStillRejectsMissingOrContradictoryFits() {
        for (quality in listOf(null, CorrespondenceQuality.BAD)) {
            assertFalse(canAnchorAlignment(quality, true, true, true, allowProvisional = true))
        }
    }

    @Test
    fun optionalAnchoringStillRequiresLiveCameraAndReferenceTracking() {
        for (quality in listOf(CorrespondenceQuality.DOUBTFUL, CorrespondenceQuality.ACCEPTABLE)) {
            assertFalse(canAnchorAlignment(quality, false, true, true, allowProvisional = true))
            assertFalse(canAnchorAlignment(quality, true, false, true, allowProvisional = true))
            assertFalse(canAnchorAlignment(quality, true, true, false, allowProvisional = true))
        }
    }

    @Test
    fun anchoredOpacityAppliesToSelectedAndComparedPartsIncludingFullyTransparent() {
        val part = part("PLATE", "IfcPlate")
        for (style in AssemblyVisualStyle.entries) {
            for (change in listOf(null, PartChange.ADDED, PartChange.MODIFIED, PartChange.UNCHANGED)) {
                for (opacity in listOf(0f, 0.25f, 0.75f, 1f)) {
                    assertEquals(
                        opacity,
                        appearanceColor(part, style, opacity, change, selected = true, uniformOpacity = true).w,
                        0.0001f,
                    )
                }
            }
        }
        // Ordinary 3D selection still highlights the chosen part, irrespective of the AR slider.
        assertEquals(
            0.82f,
            appearanceColor(part, AssemblyVisualStyle.IFC, 0.25f, null, selected = true).w,
            0.0001f,
        )
    }

    @Test
    fun enteringArHasAnAppearanceReleaseAndEmptyDisposalPhase() {
        val releasing = nextViewerRenderStage(
            ViewerRenderStage.MODEL_ACTIVE,
            ViewerRenderEvent.REQUEST_AR,
        )
        val awaitingDisposal = nextViewerRenderStage(
            releasing,
            ViewerRenderEvent.APPEARANCE_RELEASED,
        )
        val disposed = nextViewerRenderStage(
            awaitingDisposal,
            ViewerRenderEvent.VIEWPORT_DISPOSED,
        )
        val ar = nextViewerRenderStage(disposed, ViewerRenderEvent.EMPTY_HANDOFF_COMPLETE)

        assertEquals(ViewerSurface.MODEL, releasing.surface)
        assertFalse(releasing.appearanceAttached)
        assertEquals(null, awaitingDisposal.surface)
        assertEquals(null, disposed.surface)
        assertEquals(ViewerRenderStage.AR_ACTIVE, ar)
        assertEquals(ViewerSurface.AR, ar.surface)
        assertTrue(ar.appearanceAttached)
    }

    @Test
    fun exitingArFullyDisposesItBeforeModelCanBeRecreated() {
        val releasing = nextViewerRenderStage(
            ViewerRenderStage.AR_ACTIVE,
            ViewerRenderEvent.REQUEST_MODEL,
        )
        val awaitingDisposal = nextViewerRenderStage(
            releasing,
            ViewerRenderEvent.APPEARANCE_RELEASED,
        )
        val disposed = nextViewerRenderStage(
            awaitingDisposal,
            ViewerRenderEvent.VIEWPORT_DISPOSED,
        )
        val model = nextViewerRenderStage(disposed, ViewerRenderEvent.EMPTY_HANDOFF_COMPLETE)

        assertEquals(ViewerSurface.AR, releasing.surface)
        assertFalse(releasing.appearanceAttached)
        assertEquals(null, awaitingDisposal.surface)
        assertEquals(null, disposed.surface)
        assertEquals(ViewerRenderStage.MODEL_ACTIVE, model)
    }

    @Test
    fun closeCannotSkipAppearanceOrViewportDisposalAndTransitionsIgnoreReentry() {
        val releasing = nextViewerRenderStage(
            ViewerRenderStage.MODEL_ACTIVE,
            ViewerRenderEvent.REQUEST_CLOSE,
        )

        assertEquals(
            releasing,
            nextViewerRenderStage(releasing, ViewerRenderEvent.REQUEST_AR),
        )
        assertEquals(ViewerSurface.MODEL, releasing.surface)
        assertFalse(releasing.appearanceAttached)

        val awaitingDisposal = nextViewerRenderStage(
            releasing,
            ViewerRenderEvent.APPEARANCE_RELEASED,
        )
        val disposed = nextViewerRenderStage(
            awaitingDisposal,
            ViewerRenderEvent.VIEWPORT_DISPOSED,
        )
        assertEquals(null, awaitingDisposal.surface)
        assertEquals(ViewerRenderStage.MODEL_DISPOSED_FOR_CLOSE, disposed)
        assertEquals(
            disposed,
            nextViewerRenderStage(disposed, ViewerRenderEvent.EMPTY_HANDOFF_COMPLETE),
        )
    }

    @Test
    fun recognizesBothIfcFastenerClassesFromExistingPackages() {
        assertTrue(part("OTHER", "IfcFastener").isFastenerPart())
        assertTrue(part("OTHER", "IfcMechanicalFastener").isFastenerPart())
        assertTrue(part("BOLT_GROUP", "").isFastenerPart())
        assertFalse(part("PLATE", "IfcPlate").isFastenerPart())
    }

    @Test
    fun newPackagesDoNotTreatFabricationMarksAsFasteners() {
        val mark = part(
            kind = "FABRICATION_MARK",
            ifcClass = "IfcMechanicalFastener",
            fastenerCategory = "FABRICATION_MARK",
        )
        val bolt = part(
            kind = "BOLT_GROUP",
            ifcClass = "IfcMechanicalFastener",
            fastenerCategory = "PHYSICAL_FASTENER",
        )

        assertTrue(mark.isFabricationMarkPart())
        assertFalse(mark.isFastenerPart())
        assertFalse(bolt.isFabricationMarkPart())
        assertTrue(bolt.isFastenerPart())
    }

    @Test
    fun recognizesFastenersConnectedFromAnotherAssembly() {
        assertTrue(
            part(
                kind = "BOLT_GROUP",
                ifcClass = "IfcMechanicalFastener",
                external = true,
            ).isExternalConnectedFastener(),
        )
        assertFalse(part("BOLT_GROUP", "IfcMechanicalFastener").isExternalConnectedFastener())
    }

    @Test
    fun cameraHomeFramesLargeAssemblyAndKeepsSmallAssemblyDefault() {
        val small = cameraFrame(listOf(part(bounds = Bounds(listOf(0f, 0f, 0f), listOf(1f, 1f, 1f)))))
        val large = cameraFrame(listOf(part(bounds = Bounds(listOf(-5f, -2f, -1f), listOf(5f, 2f, 1f)))))

        assertEquals(0.5f, small.target.x, 0.001f)
        assertEquals(0.5f, small.target.y, 0.001f)
        assertEquals(0.5f, small.target.z, 0.001f)
        assertEquals(2.75f, small.home.z - small.target.z, 0.001f)
        assertTrue(large.home.z > 11f)
        assertEquals(large.home.z * 0.145f, large.home.y, 0.001f)
    }

    @Test
    fun cameraHomeShowsAWholeBeamUprightAndComesCloserInLandscape() {
        // 6.4 m beam, 0.3 m wide, 0.5 m high, like a real end-plate beam.  Upright the horizontal field of view
        // is the narrow one; 1.15 × diagonal (7.4 m) cut both ends off the screen.
        val beam = listOf(part(bounds = Bounds(listOf(-3.2f, -0.15f, -0.25f), listOf(3.2f, 0.15f, 0.25f))))
        val upright = cameraFrame(beam, 0.68f)
        val distance = upright.home.z - upright.target.z
        val halfWidthVisible = (distance - 0.25f) * kotlin.math.tan(VIEWER_VERTICAL_FOV_RADIANS / 2.0).toFloat() * 0.68f
        assertTrue("$distance m", distance > 11f)
        assertTrue(halfWidthVisible >= 3.2f)
        val landscape = cameraFrame(beam, 1.8f)
        assertTrue(landscape.home.z - landscape.target.z < distance * 0.5f)
        // Before the viewport is laid out the upright assumption applies.
        assertEquals(distance, cameraFrame(beam).home.z - cameraFrame(beam).target.z, 0.001f)
    }

    @Test
    fun partLabelsUseThePartTopCenterAndIgnoreMissingPositionNumbers() {
        val labels = partLabelVisuals(
            listOf(
                part(
                    positionNumber = "100001.7",
                    bounds = Bounds(listOf(-1f, 2f, 3f), listOf(3f, 4f, 7f)),
                ),
                part(positionNumber = ""),
            ),
        )

        assertEquals(1, labels.size)
        assertEquals("100001.7", labels.single().part.positionNumber)
        assertEquals("7", labels.single().text)
        assertEquals(1f, labels.single().position.x, 0.001f)
        assertTrue(labels.single().position.y > 4f)
        assertEquals(5f, labels.single().position.z, 0.001f)
    }

    @Test
    fun displayPartPositionNumberShowsOnlySuffixAfterSixDigitDrawingNumber() {
        assertEquals("7", displayPartPositionNumber("100001.7"))
        assertEquals("501", displayPartPositionNumber("191001.501"))
        assertEquals("P1001", displayPartPositionNumber("P1001"))
        assertEquals("12345.9", displayPartPositionNumber("12345.9"))
    }

    @Test
    fun projectedSelectionUsesNearestMatchingScreenBounds() {
        val large = part(positionNumber = "100001.1")
        val small = part(positionNumber = "100001.2")
        val selected = selectProjectedPart(
            listOf(
                PartScreenBounds(large, left = 0f, top = 0f, right = 300f, bottom = 300f),
                PartScreenBounds(small, left = 120f, top = 120f, right = 170f, bottom = 170f),
            ),
            x = 151f,
            y = 150f,
            tolerancePx = 8f,
        )

        assertEquals(small, selected)
    }

    @Test
    fun projectedSelectionUsesToleranceAroundThinParts() {
        val thin = part(positionNumber = "100001.3")
        val selected = selectProjectedPart(
            listOf(PartScreenBounds(thin, left = 100f, top = 100f, right = 104f, bottom = 240f)),
            x = 112f,
            y = 160f,
            tolerancePx = 12f,
        )

        assertEquals(thin, selected)
    }

    @Test
    fun projectedSelectionPrefersTheClosestVisiblePart() {
        val foreground = part(positionNumber = "100001.4")
        val background = part(positionNumber = "100001.5")
        val selected = selectProjectedPart(
            listOf(
                PartScreenBounds(
                    foreground,
                    left = 0f,
                    top = 0f,
                    right = 300f,
                    bottom = 300f,
                    depth = 2f,
                ),
                PartScreenBounds(
                    background,
                    left = 120f,
                    top = 120f,
                    right = 170f,
                    bottom = 170f,
                    depth = 5f,
                ),
            ),
            x = 151f,
            y = 150f,
            tolerancePx = 8f,
        )

        assertEquals(foreground, selected)
    }

    @Test
    fun entityIndexResolvesExactNamesAndRenderableWrappers() {
        val plate = part(kind = "PLATE", ifcClass = "IfcPlate")
        val beam = part(kind = "BEAM", ifcClass = "IfcBeam")
        val index = PartEntityIndex(listOf(plate, beam))

        assertEquals(plate, index.find(plate.gltfNodeName))
        assertEquals(beam, index.find("mesh:${beam.guid}:primitive-0"))
        assertEquals(null, index.find("unrelated-node"))
    }

    @Test
    fun pickSamplesCaptureAndroidCoordinatesWithoutKeepingAMotionEvent() {
        val samples = pickSamples(TapPoint(50f, 25f), width = 100, height = 80)

        assertEquals(50 to 54, samples.first())
        assertEquals(samples.distinct().size, samples.size)
        assertTrue(samples.all { (x, y) -> x in 0..99 && y in 0..79 })
    }

    @Test
    fun backCollapsesArUiBeforeLeavingArAndNeverClosesTheViewerDirectly() {
        assertEquals(
            ViewerBackAction.IgnoreTransition,
            viewerBackAction(
                arActive = true,
                arControlsExpanded = true,
                arFullscreen = true,
                transitionInProgress = true,
            ),
        )
        assertEquals(
            ViewerBackAction.CollapseArControls,
            viewerBackAction(arActive = true, arControlsExpanded = true, arFullscreen = true),
        )
        assertEquals(
            ViewerBackAction.ExitArFullscreen,
            viewerBackAction(arActive = true, arControlsExpanded = false, arFullscreen = true),
        )
        assertEquals(
            ViewerBackAction.ExitAr,
            viewerBackAction(arActive = true, arControlsExpanded = false, arFullscreen = false),
        )
        assertEquals(
            ViewerBackAction.CloseViewer,
            viewerBackAction(arActive = false, arControlsExpanded = false, arFullscreen = false),
        )
    }

    private fun part(
        kind: String = "PLATE",
        ifcClass: String = "IfcPlate",
        positionNumber: String = "100001.1",
        bounds: Bounds = Bounds(listOf(0f, 0f, 0f), listOf(1f, 1f, 1f)),
        external: Boolean = false,
        fastenerCategory: String? = null,
    ) = Part(
        guid = "guid-$kind-$ifcClass",
        positionNumber = positionNumber,
        kind = kind,
        gltfNodeName = "part-$kind-$ifcClass",
        boundsAssemblyM = bounds,
        inspectable = kind != "BOLT_GROUP",
        properties = JsonObject(
            buildMap {
                put("ifcClass", JsonPrimitive(ifcClass))
                fastenerCategory?.let {
                    put("fastenerCategory", JsonPrimitive(it))
                }
                if (external) {
                    put("externalFastener", JsonPrimitive("true"))
                    put("fastenerScope", JsonPrimitive("EXTERNAL_CONNECTED"))
                }
            },
        ),
    )
}
