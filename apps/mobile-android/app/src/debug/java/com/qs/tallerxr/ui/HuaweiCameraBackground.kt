package com.qs.tallerxr.ui

import android.opengl.GLES30
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager
import com.google.android.filament.Texture
import com.google.android.filament.VertexBuffer
import com.huawei.hiar.ARFrame
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setExternalTexture
import io.github.sceneview.material.setParameter
import io.github.sceneview.math.Transform
import io.github.sceneview.utils.OpenGL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Huawei's OES camera image and the CAD share one Filament surface (also used by the loupe). */
internal class HuaweiCameraBackground(private val loader: MaterialLoader) {
    private val engine = loader.engine
    val entity = EntityManager.get().create()
    val textureId = OpenGL.createExternalTextureId().also { check(it != 0) { "Kein OpenGL-Kamerakontext" } }
    private val texture = Texture.Builder().sampler(Texture.Sampler.SAMPLER_EXTERNAL)
        .format(Texture.InternalFormat.RGB16F).importTexture(textureId.toLong()).build(engine)
    private val material = loader.createMaterial("materials/camera_stream_flat.filamat").apply {
        defaultInstance.setParameter("uvTransform", Transform())
        defaultInstance.setExternalTexture("cameraTexture", texture)
    }
    private val vertices = VertexBuffer.Builder().vertexCount(4).bufferCount(2)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .attribute(VertexBuffer.VertexAttribute.UV0, 1, VertexBuffer.AttributeType.FLOAT2, 0, 8)
        .build(engine).apply {
            setBufferAt(engine, 0, FloatBuffer.wrap(floatArrayOf(
                -1f, 1f, 1f, -1f, -1f, 1f, 1f, 1f, 1f, 1f, -1f, 1f,
            )))
        }
    private val indices = IndexBuffer.Builder().indexCount(6)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine).apply {
            setBuffer(engine, ShortBuffer.wrap(shortArrayOf(0, 1, 2, 2, 1, 3)))
        }
    // Same screen corners and UV convention as Huawei's official TextureDisplay example.
    private val uv = floats(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f))
    private var firstFrame = true

    init {
        RenderableManager.Builder(1).castShadows(false).receiveShadows(false).culling(false)
            .priority(7).geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertices, indices)
            .material(0, material.defaultInstance).build(engine, entity)
    }

    fun update(frame: ARFrame) {
        if (firstFrame || frame.hasDisplayGeometryChanged()) {
            // A fresh buffer belongs to each upload; never mutate memory still used by Filament.
            val transformed = floats(FloatArray(8))
            uv.rewind()
            frame.transformDisplayUvCoords(uv, transformed)
            // Huawei returns OpenGL sampling UVs. Filament's camera material expects the
            // opposite V convention (ARCameraStream makes this same conversion for ARCore).
            // Apply it AFTER display rotation; flipping screen X would break landscape views.
            for (index in 1 until transformed.capacity() step 2) {
                transformed.put(index, 1f - transformed[index])
            }
            transformed.rewind()
            vertices.setBufferAt(engine, 1, transformed)
            firstFrame = false
        }
    }

    fun destroy() {
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        engine.destroyVertexBuffer(vertices)
        engine.destroyIndexBuffer(indices)
        loader.destroyMaterial(material)
        engine.destroyTexture(texture)
        engine.flushAndWait()
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }
}

private fun floats(values: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
    .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); rewind() }
