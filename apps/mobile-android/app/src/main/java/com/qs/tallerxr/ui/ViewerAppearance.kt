package com.qs.tallerxr.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.View
import com.google.android.filament.gltfio.FilamentInstance
import com.qs.tallerxr.data.Part
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.loaders.MaterialLoader
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive

internal enum class AssemblyVisualStyle(val label: String) {
    IFC("IFC"),
    STEEL("Stahl"),
    PARTS("Bauteile"),
    INSPECTION("Prüfung"),
    XRAY("Röntgen"),
}

internal data class AssemblyAppearance(
    val style: AssemblyVisualStyle = AssemblyVisualStyle.IFC,
    val opacity: Float = 1f,
    val cavity: Boolean = true,
    val fastenersVisible: Boolean = false,
    val fabricationMarksVisible: Boolean = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceControls(
    appearance: AssemblyAppearance,
    compact: Boolean = false,
    cavitySupported: Boolean = true,
    onChange: (AssemblyAppearance) -> Unit,
) {
    if (compact) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppearanceButtons(appearance, cavitySupported, onChange)
            Text(
                "Deckkraft ${(appearance.opacity * 100).roundToInt()} %",
            )
            Slider(
                value = appearance.opacity,
                onValueChange = { value -> onChange(appearance.copy(opacity = value)) },
                valueRange = 0.2f..1f,
                steps = 3,
                modifier = Modifier.width(180.dp),
            )
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            // Wrapping, not scrolling: a button cut off at the edge is one nobody finds.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppearanceButtons(appearance, cavitySupported, onChange)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Deckkraft ${(appearance.opacity * 100).roundToInt()} %",
                    modifier = Modifier.padding(top = 11.dp),
                )
                Slider(
                    value = appearance.opacity,
                    onValueChange = { value -> onChange(appearance.copy(opacity = value)) },
                    valueRange = 0.2f..1f,
                    steps = 3,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AppearanceButtons(
    appearance: AssemblyAppearance,
    cavitySupported: Boolean,
    onChange: (AssemblyAppearance) -> Unit,
) {
    AssistChip(
        onClick = {
            val values = AssemblyVisualStyle.entries
            onChange(
                appearance.copy(
                    style = values[(appearance.style.ordinal + 1) % values.size],
                ),
            )
        },
        label = { Text("Ansicht: ${appearance.style.label}", maxLines = 1) },
    )
    AppearanceToggleButton(
        selected = appearance.cavity && cavitySupported,
        enabled = cavitySupported,
        onClick = { onChange(appearance.copy(cavity = !appearance.cavity)) },
        label = "Cavity",
    )
    AppearanceToggleButton(
        selected = appearance.fastenersVisible,
        onClick = {
            onChange(appearance.copy(fastenersVisible = !appearance.fastenersVisible))
        },
        label = "Verbindungsmittel",
    )
    AppearanceToggleButton(
        selected = appearance.fabricationMarksVisible,
        onClick = {
            onChange(
                appearance.copy(
                    fabricationMarksVisible = !appearance.fabricationMarksVisible,
                ),
            )
        },
        label = "Markierungen",
    )
}

@Composable
private fun AppearanceToggleButton(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
) {
    FilterChip(selected = selected && enabled, enabled = enabled, onClick = onClick, label = { Text(label, maxLines = 1) })
}

@Composable
internal fun ConfigureCavity(view: View, enabled: Boolean) {
    DisposableEffect(view, enabled) {
        val previousOptions = view.ambientOcclusionOptions
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
                this.enabled = enabled
                aoType = View.AmbientOcclusionOptions.AmbientOcclusionType.GTAO
                radius = 0.32f
                power = 1.35f
                intensity = 1.15f
                resolution = 0.5f
                quality = View.QualityLevel.HIGH
                lowPassFilter = View.QualityLevel.MEDIUM
                upsampling = View.QualityLevel.HIGH
                bentNormals = true
            }
        onDispose {
            view.ambientOcclusionOptions = previousOptions
        }
    }
}

private data class RenderableBinding(
    val entity: Int,
    val instance: Int,
    val part: Part,
    val originals: List<MaterialInstance>,
)

@Composable
internal fun ApplyAssemblyAppearance(
    engine: Engine,
    materialLoader: MaterialLoader,
    modelInstance: FilamentInstance?,
    parts: List<Part>,
    appearance: AssemblyAppearance,
    partChanges: Map<String, PartChange> = emptyMap(),
    selectedPartGuid: String? = null,
    uniformOpacity: Boolean = false,
) {
    val bindings = remember(modelInstance, parts) {
        if (modelInstance == null) {
            emptyList()
        } else {
            val manager = engine.renderableManager
            parts.mapNotNull { part ->
                val entity = modelInstance.asset.getFirstEntityByName(part.gltfNodeName)
                if (entity == 0) return@mapNotNull null
                val instance = manager.getInstance(entity)
                if (instance == 0) return@mapNotNull null
                RenderableBinding(
                    entity = entity,
                    instance = instance,
                    part = part,
                    originals = List(manager.getPrimitiveCount(instance)) { primitive ->
                        manager.getMaterialInstanceAt(instance, primitive)
                    },
                )
            }
        }
    }

    DisposableEffect(
        bindings,
        appearance.fastenersVisible,
        appearance.fabricationMarksVisible,
    ) {
        val manager = engine.renderableManager
        bindings.filter {
            it.part.isFastenerPart() || it.part.isFabricationMarkPart()
        }.forEach { binding ->
            if (!manager.bindingIsAlive(binding)) return@forEach
            val visible = when {
                binding.part.isFastenerPart() -> appearance.fastenersVisible
                else -> appearance.fabricationMarksVisible
            }
            manager.setLayerMask(
                binding.instance,
                0xff,
                if (visible) 0xff else 0x00,
            )
        }
        onDispose {
            bindings.filter {
                it.part.isFastenerPart() || it.part.isFabricationMarkPart()
            }.forEach { binding ->
                if (manager.bindingIsAlive(binding)) {
                    manager.setLayerMask(binding.instance, 0xff, 0xff)
                }
            }
        }
    }

    DisposableEffect(
        bindings,
        appearance.style,
        appearance.opacity,
        partChanges,
        selectedPartGuid,
        uniformOpacity,
    ) {
        val manager = engine.renderableManager
        val originalOnly = appearance.style == AssemblyVisualStyle.IFC &&
            appearance.opacity >= 0.99f && partChanges.isEmpty() && selectedPartGuid == null
        val overrides = mutableMapOf<Int, MaterialInstance>()
        if (!originalOnly) {
            bindings.forEach { binding ->
                if (!manager.bindingIsAlive(binding)) return@forEach
                val color = appearanceColor(
                    part = binding.part,
                    style = appearance.style,
                    opacity = appearance.opacity,
                    change = partChanges[binding.part.guid],
                    selected = binding.part.guid == selectedPartGuid,
                    uniformOpacity = uniformOpacity,
                )
                val colorKey = colorKey(color, appearance.style)
                val material = overrides.getOrPut(colorKey) {
                    if (appearance.style == AssemblyVisualStyle.XRAY) {
                        materialLoader.createUnlitColorInstance(color)
                    } else {
                        materialLoader.createColorInstance(
                            color,
                            metallic = if (appearance.style == AssemblyVisualStyle.INSPECTION) 0.05f else 0.24f,
                            roughness = if (appearance.style == AssemblyVisualStyle.XRAY) 0.8f else 0.62f,
                            reflectance = 0.45f,
                        )
                    }.apply {
                        setDoubleSided(true)
                        setDepthWrite(appearance.opacity >= 0.99f)
                    }
                }
                repeat(manager.getPrimitiveCount(binding.instance)) { primitive ->
                    manager.setMaterialInstanceAt(binding.instance, primitive, material)
                    manager.setBlendOrderAt(binding.instance, primitive, 10)
                }
            }
        }
        onDispose {
            restoreOriginalMaterials(manager, bindings)
            overrides.values.toSet().forEach(materialLoader::destroyMaterialInstance)
        }
    }
}

private fun restoreOriginalMaterials(
    manager: RenderableManager,
    bindings: List<RenderableBinding>,
) {
    bindings.forEach { binding ->
        if (!manager.bindingIsAlive(binding)) return@forEach
        binding.originals.forEachIndexed { primitive, material ->
            manager.setMaterialInstanceAt(binding.instance, primitive, material)
            manager.setBlendOrderAt(binding.instance, primitive, 0)
        }
    }
}

private fun RenderableManager.bindingIsAlive(binding: RenderableBinding): Boolean =
    hasComponent(binding.entity) && getInstance(binding.entity) == binding.instance

private val partPalette = listOf(
    floatArrayOf(0.24f, 0.63f, 0.83f),
    floatArrayOf(0.96f, 0.57f, 0.27f),
    floatArrayOf(0.36f, 0.76f, 0.48f),
    floatArrayOf(0.78f, 0.42f, 0.70f),
    floatArrayOf(0.90f, 0.77f, 0.25f),
    floatArrayOf(0.43f, 0.58f, 0.91f),
)

internal fun appearanceColor(
    part: Part,
    style: AssemblyVisualStyle,
    opacity: Float,
    change: PartChange?,
    selected: Boolean,
    uniformOpacity: Boolean = false,
): Float4 {
    val rgb = when {
        selected -> floatArrayOf(0.98f, 0.18f, 0.72f)
        change == PartChange.ADDED -> floatArrayOf(0.20f, 0.88f, 0.42f)
        change == PartChange.MODIFIED -> floatArrayOf(1.00f, 0.70f, 0.10f)
        change == PartChange.UNCHANGED -> floatArrayOf(0.30f, 0.38f, 0.42f)
        part.isExternalConnectedFastener() -> floatArrayOf(0.73f, 0.28f, 0.92f)
        part.isFabricationMarkPart() -> floatArrayOf(0.22f, 0.78f, 0.92f)
        else -> when (style) {
        AssemblyVisualStyle.STEEL -> floatArrayOf(0.48f, 0.62f, 0.69f)
        AssemblyVisualStyle.INSPECTION -> floatArrayOf(0.95f, 0.55f, 0.12f)
        AssemblyVisualStyle.XRAY -> floatArrayOf(0.12f, 0.82f, 0.96f)
        AssemblyVisualStyle.PARTS -> partPalette[(part.guid.hashCode() and Int.MAX_VALUE) % partPalette.size]
        AssemblyVisualStyle.IFC -> when {
            part.kind == "MAIN_PART" -> floatArrayOf(0.20f, 0.52f, 0.69f)
            part.kind == "PLATE" -> floatArrayOf(0.28f, 0.64f, 0.78f)
            part.isFastenerPart() -> floatArrayOf(0.93f, 0.49f, 0.14f)
            else -> floatArrayOf(0.42f, 0.61f, 0.70f)
        }
        }
    }
    val alpha = when {
        uniformOpacity -> opacity.coerceIn(0f, 1f)
        selected || change == PartChange.ADDED || change == PartChange.MODIFIED -> opacity.coerceAtLeast(0.82f)
        change == PartChange.UNCHANGED -> opacity.coerceAtMost(0.35f)
        style == AssemblyVisualStyle.XRAY -> opacity.coerceAtMost(0.55f)
        else -> opacity
    }
    return Float4(rgb[0], rgb[1], rgb[2], alpha)
}

internal fun Part.isFastenerPart(): Boolean {
    val ifcClass = (properties["ifcClass"] as? JsonPrimitive)?.content
    val category = (properties["fastenerCategory"] as? JsonPrimitive)?.content
    if (category != null) return category == "PHYSICAL_FASTENER"
    return kind == "BOLT_GROUP" ||
        ifcClass == "IfcMechanicalFastener" ||
        ifcClass == "IfcFastener"
}

internal fun Part.isFabricationMarkPart(): Boolean {
    val category = (properties["fastenerCategory"] as? JsonPrimitive)?.content
    return kind == "FABRICATION_MARK" || category == "FABRICATION_MARK"
}

internal fun Part.isExternalConnectedFastener(): Boolean {
    val external = (properties["externalFastener"] as? JsonPrimitive)?.content
    val scope = (properties["fastenerScope"] as? JsonPrimitive)?.content
    return isFastenerPart() && (external == "true" || scope == "EXTERNAL_CONNECTED")
}

private fun colorKey(color: Float4, style: AssemblyVisualStyle): Int {
    fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255).roundToInt()
    return style.ordinal.shl(28) xor channel(color.x).shl(20) xor
        channel(color.y).shl(12) xor channel(color.z).shl(4) xor channel(color.w)
}
