package com.qs.tallerxr.ui

import com.qs.tallerxr.ar.LandmarkVisibility
import com.qs.tallerxr.data.Bounds
import com.qs.tallerxr.data.Part
import com.qs.tallerxr.importer.GlbNodeMesh
import com.qs.tallerxr.importer.GlbReader
import org.junit.Assert.*
import org.junit.Test

class ArEdgeOcclusionTest {
    private fun plate(x0: Float, x1: Float, y0: Float, y1: Float, z: Float) = floatArrayOf(
        x0,y0,z, x1,y0,z, x1,y1,z, x0,y0,z, x1,y1,z, x0,y1,z,
    )
    private fun part(name: String, kind: String = "MAIN_PART") = Part(
        guid = name, positionNumber = name, kind = kind, gltfNodeName = name,
        boundsAssemblyM = Bounds(listOf(-1f,-1f,-1f), listOf(1f,1f,1f)), inspectable = true,
    )

    @Test fun `the occluder preserves openings instead of filling a bounding box`() {
        val opening = plate(-2f,-0.3f,-2f,2f,0f) + plate(0.3f,2f,-2f,2f,0f) +
            plate(-0.3f,0.3f,-2f,-0.3f,0f) + plate(-0.3f,0.3f,0.3f,2f,0f)
        val result = edgeOcclusionMesh(listOf(GlbNodeMesh(0, "plate", opening)), listOf(part("plate")), false)
        val visibility = LandmarkVisibility(result.triangles)
        assertTrue(visibility.isVisible(floatArrayOf(0f,0f,3f), floatArrayOf(0f,0f,-1f)))
        assertFalse(visibility.isVisible(floatArrayOf(0f,0f,3f), floatArrayOf(1f,0f,-1f)))
        assertArrayEquals(opening, result.triangles, 0f)
    }

    @Test fun `hidden fasteners and nonphysical marks cannot occlude edges`() {
        val body = GlbNodeMesh(0,"body",plate(-3f,-2f,-1f,1f,0f))
        val bolt = GlbNodeMesh(1,"bolt",plate(-0.3f,0.3f,-0.3f,0.3f,0f))
        val mark = GlbNodeMesh(2,"mark",plate(1f,2f,-1f,1f,0f))
        val parts = listOf(part("body"), part("bolt","BOLT_GROUP"), part("mark","FABRICATION_MARK"))
        val hidden = edgeOcclusionMesh(listOf(body,bolt,mark),parts,false)
        val shown = edgeOcclusionMesh(listOf(body,bolt,mark),parts,true)
        assertEquals(body.triangleCount, hidden.triangleCount)
        assertEquals(body.triangleCount + bolt.triangleCount, shown.triangleCount)
        val camera = floatArrayOf(0f,0f,3f)
        assertTrue(LandmarkVisibility(hidden.triangles).isVisible(camera, floatArrayOf(0f,0f,-0.1f)))
        assertFalse(LandmarkVisibility(shown.triangles).isVisible(camera, floatArrayOf(0f,0f,-0.1f)))
        assertTrue(LandmarkVisibility(shown.triangles).isVisible(camera, floatArrayOf(1.5f,0f,-0.1f)))
    }

    @Test fun `unclassified geometry remains an occluder and retains its assembly coordinates`() {
        val imported = GlbNodeMesh(1,null,plate(12f,14f,-3f,-2f,7f))
        val result = edgeOcclusionMesh(listOf(imported), emptyList(), false)
        assertArrayEquals(imported.triangles, result.triangles, 0f)
        assertArrayEquals(floatArrayOf(12f,-3f,7f),result.boundsMin,0f)
        assertArrayEquals(floatArrayOf(14f,-2f,7f),result.boundsMax,0f)
    }

    @Test fun `the same occluder exposes the opposite edges after changing viewpoint`() {
        val result = edgeOcclusionMesh(listOf(
            GlbNodeMesh(0,"front",plate(-1f,1f,-1f,1f,0.1f)),
            GlbNodeMesh(1,"rear",plate(-1f,1f,-1f,1f,-0.1f)),
        ), emptyList(), false)
        val visibility = LandmarkVisibility(result.triangles)
        for (side in listOf(-1f,1f)) {
            val camera = floatArrayOf(0f,0f,3f*side)
            assertTrue(visibility.isVisible(camera,floatArrayOf(0f,0f,0.1f*side)))
            assertFalse(visibility.isVisible(camera,floatArrayOf(0f,0f,-0.1f*side)))
        }
    }

    @Test fun `invalid or empty geometry fails instead of reporting all edges as visible`() {
        assertThrows(IllegalArgumentException::class.java) { edgeOcclusionMesh(emptyList(),emptyList(),false) }
        assertThrows(IllegalArgumentException::class.java) {
            edgeOcclusionMesh(listOf(GlbNodeMesh(0,null,FloatArray(9) { Float.NaN })),emptyList(),false)
        }
    }

    @Test fun `real profile geometry is retained without replacing its concave shape`() {
        val scene = GlbReader.parse(javaClass.getResourceAsStream("/importer/heb-assembly.glb")!!.use { it.readBytes() })
        val result = edgeOcclusionMesh(scene.meshes, emptyList(), false)
        assertEquals(scene.triangleCount,result.triangleCount)
        var offset = 0
        scene.meshes.forEach { mesh ->
            assertArrayEquals(mesh.triangles,result.triangles.copyOfRange(offset,offset+mesh.triangles.size),0f)
            offset += mesh.triangles.size
        }
    }
}
