package com.qs.tallerxr.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Box as FilamentBox
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.VertexBuffer
import com.qs.tallerxr.data.Part
import com.qs.tallerxr.importer.GlbNodeMesh
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** The actual displayed physical surfaces, including holes, in the model's assembly frame. */
internal fun edgeOcclusionMesh(
    meshes: List<GlbNodeMesh>,
    parts: List<Part>,
    fastenersVisible: Boolean,
): GlbNodeMesh {
    val partIndex = PartEntityIndex(parts)
    val physical = meshes.filter { mesh ->
        val part = partIndex.find(mesh.name)
        part == null || (!part.isFabricationMarkPart() && (fastenersVisible || !part.isFastenerPart()))
    }
    val positions = FloatArray(physical.sumOf { it.triangles.size })
    var offset = 0
    physical.forEach { mesh ->
        mesh.triangles.copyInto(positions, offset)
        offset += mesh.triangles.size
    }
    require(positions.isNotEmpty() && positions.size % 9 == 0 && positions.all(Float::isFinite)) {
        "Das Modell enthält keine lesbaren Flächen für sichtbare Kanten."
    }
    return GlbNodeMesh(-1, null, positions)
}

private class EdgeOcclusionResources(
    private val engine: Engine,
    private val loader: MaterialLoader,
    mesh: GlbNodeMesh,
) {
    val vertices: VertexBuffer = VertexBuffer.Builder()
        .bufferCount(1).vertexCount(mesh.triangles.size / 3)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .build(engine).apply { setBufferAt(engine, 0, FloatBuffer.wrap(mesh.triangles)) }
    val indices: IndexBuffer = IndexBuffer.Builder()
        .indexCount(mesh.triangles.size / 3).bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine).apply { setBuffer(engine, IntBuffer.wrap(IntArray(mesh.triangles.size / 3) { it })) }

    // IMPORTANT: keep this in the BLENDED pass even though it writes only depth.
    // An opaque depth mask would run before the opaque AR camera background (priority 7)
    // and leave black holes in the video. The blended pass runs AFTER that background.
    // Priority 6 keeps the model's existing transparent appearance (priority 4), then masks
    // just the subsequent edge pass (priority 7). No dependency on either opacity slider.
    val material = loader.createUnlitColorInstance(Color.Transparent).apply {
        setCullingMode(Material.CullingMode.NONE)
        setColorWrite(false)
        setDepthWrite(true)
        setDepthCulling(true)
    }

    fun destroy() {
        loader.destroyMaterialInstance(material)
        engine.destroyVertexBuffer(vertices)
        engine.destroyIndexBuffer(indices)
    }
}

@Composable
internal fun SceneScope.EdgeOcclusionNode(engine: Engine, loader: MaterialLoader, mesh: GlbNodeMesh) {
    val resources = remember(engine, mesh) { EdgeOcclusionResources(engine, loader, mesh) }
    // Node disposal precedes its buffers/material, as for the existing feature edge node.
    DisposableEffect(resources) { onDispose { resources.destroy() } }
    MeshNode(
        primitiveType = com.google.android.filament.RenderableManager.PrimitiveType.TRIANGLES,
        vertexBuffer = resources.vertices,
        indexBuffer = resources.indices,
        boundingBox = FilamentBox(
            (mesh.boundsMin[0] + mesh.boundsMax[0]) / 2f,
            (mesh.boundsMin[1] + mesh.boundsMax[1]) / 2f,
            (mesh.boundsMin[2] + mesh.boundsMax[2]) / 2f,
            (mesh.boundsMax[0] - mesh.boundsMin[0]) / 2f + 0.001f,
            (mesh.boundsMax[1] - mesh.boundsMin[1]) / 2f + 0.001f,
            (mesh.boundsMax[2] - mesh.boundsMin[2]) / 2f + 0.001f,
        ),
        materialInstance = resources.material,
        apply = {
            setPriority(6)
            isShadowCaster = false
            isShadowReceiver = false
        },
    )
}
