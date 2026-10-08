/*
 * QS Taller XR - IFC to GLB conversion on the phone.
 *
 * Runs on top of web-ifc (MPL-2.0), inside an offline WebView on Android and under Node for the
 * converter tests.  One IFC file becomes one GLB in the QS XR space of ADR 0001: metres, Y up,
 * right handed, one named node per IFC product, plus the part metadata the viewer needs.
 *
 * web-ifc already evaluates the IFC geometry, including the boolean cuts Tekla writes for mitred
 * ends and copes, and delivers it in metres with Y up.  This file only bakes the placements,
 * recentres the part, classifies Tekla fasteners the way the PC exporter does and writes the GLB.
 */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.QsIfcConverter = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var MAXIMUM_TRIANGLES = 3000000;

  var CLASS_NAMES = {
    IFCBEAM: "IfcBeam", IFCCOLUMN: "IfcColumn", IFCMEMBER: "IfcMember", IFCPLATE: "IfcPlate",
    IFCMECHANICALFASTENER: "IfcMechanicalFastener", IFCFASTENER: "IfcFastener",
    IFCDISCRETEACCESSORY: "IfcDiscreteAccessory", IFCBUILDINGELEMENTPROXY: "IfcBuildingElementProxy",
    IFCCOVERING: "IfcCovering", IFCSLAB: "IfcSlab", IFCWALL: "IfcWall", IFCFOOTING: "IfcFooting",
    IFCRAILING: "IfcRailing", IFCSTAIR: "IfcStair", IFCSTAIRFLIGHT: "IfcStairFlight",
    IFCREINFORCINGBAR: "IfcReinforcingBar", IFCELEMENTASSEMBLY: "IfcElementAssembly",
  };

  var PHYSICAL_STANDARDS = ["4014", "4017", "7990", "DIN4014", "DIN4017", "DIN7990", "EN143994",
    "FISCHER", "HILTI", "ISO4014", "ISO4017", "NELSON"];
  var HOLE_STANDARDS = ["LOCH", "ZINCHOLES"];
  var MARK_PATTERN = /(?:PULVER|POWDER|BRENN|SCRIBE|ANREISS|MARKIER|SIGNIER)/i;
  var PHYSICAL_NAME_PATTERN = /^(?:M\d+(?:[.,]\d+)?(?:\b|[*X-])|FAZ\b|HAS\b|HST\b|NELSON\b)/i;

  function text(value) {
    if (value === null || value === undefined) return null;
    var raw = typeof value === "object" && "value" in value ? value.value : value;
    if (raw === null || raw === undefined) return null;
    var result = String(raw).trim();
    return result.length ? result : null;
  }

  function code(value) {
    return (value || "").toUpperCase().replace(/[^A-Z0-9]+/g, "");
  }

  function vectorToArray(vector) {
    var result = [];
    for (var i = 0; i < vector.size(); i++) result.push(vector.get(i));
    return result;
  }

  /** One pass over the property relations: product id -> property set ids. */
  function indexPropertySets(api, WebIFC, modelID) {
    var index = new Map();
    var relations = vectorToArray(api.GetLineIDsWithType(modelID, WebIFC.IFCRELDEFINESBYPROPERTIES));
    relations.forEach(function (relationID) {
      var relation;
      try { relation = api.GetLine(modelID, relationID, false); } catch (error) { return; }
      var definition = relation && relation.RelatingPropertyDefinition;
      if (!definition || !relation.RelatedObjects) return;
      relation.RelatedObjects.forEach(function (reference) {
        if (!reference || typeof reference.value !== "number") return;
        if (!index.has(reference.value)) index.set(reference.value, []);
        index.get(reference.value).push(definition.value);
      });
    });
    return index;
  }

  /** Flat name -> value map of the single value properties of one product. */
  function readProperties(api, modelID, propertySetIDs, cache) {
    var result = {};
    (propertySetIDs || []).forEach(function (setID) {
      var values = cache.get(setID);
      if (!values) {
        values = {};
        try {
          var set = api.GetLine(modelID, setID, false);
          (set.HasProperties || []).forEach(function (reference) {
            try {
              var property = api.GetLine(modelID, reference.value, false);
              var name = text(property.Name);
              var value = text(property.NominalValue);
              if (name && value !== null) values[name] = value;
            } catch (error) { /* an unreadable property never blocks the part */ }
          });
        } catch (error) { /* not an IfcPropertySet, e.g. quantities */ }
        cache.set(setID, values);
      }
      Object.keys(values).forEach(function (name) {
        if (!(name in result)) result[name] = values[name];
      });
    });
    return result;
  }

  function property(properties, names) {
    var keys = Object.keys(properties);
    for (var i = 0; i < names.length; i++) {
      var wanted = names[i].toLowerCase();
      for (var k = 0; k < keys.length; k++) {
        if (keys[k].toLowerCase() === wanted) return properties[keys[k]];
      }
    }
    return null;
  }

  /** Mirrors classify_mechanical_fastener of the PC exporter: never trust the IFC class alone. */
  function classifyFastener(properties) {
    var standard = code(property(properties, ["BOLT_STANDARD", "Bolt standard"]));
    var name = property(properties, ["NAME", "NAME_FULL", "Bolt name"]) || "";
    var material = code(property(properties, ["MATERIAL", "GRADE"]));
    if (HOLE_STANDARDS.indexOf(standard) >= 0 || HOLE_STANDARDS.indexOf(material) >= 0) return "MANUFACTURING_VOID";
    if (MARK_PATTERN.test(name)) return "FABRICATION_MARK";
    if (PHYSICAL_STANDARDS.indexOf(standard) >= 0 || PHYSICAL_NAME_PATTERN.test(name)) return "PHYSICAL_FASTENER";
    return "UNKNOWN_MECHANICAL_FASTENER";
  }

  function colourKey(colour) {
    return [colour.x, colour.y, colour.z, colour.w].map(function (v) { return Math.round(v * 255); }).join(",");
  }

  function convert(api, WebIFC, modelID, options) {
    options = options || {};
    var maximumTriangles = options.maximumTriangles || MAXIMUM_TRIANGLES;
    var propertyIndex = indexPropertySets(api, WebIFC, modelID);
    var propertyCache = new Map();

    var products = [];
    var materials = [];
    var materialIndex = new Map();
    var triangleCount = 0;
    var skippedVoids = 0;
    var failure = null;

    api.StreamAllMeshes(modelID, function (mesh) {
      if (failure) return;
      var expressID = mesh.expressID;
      var line;
      try { line = api.GetLine(modelID, expressID, false); } catch (error) { line = {}; }
      var typeName = "";
      try { typeName = api.GetNameFromTypeCode(api.GetLineType(modelID, expressID)) || ""; } catch (error) { typeName = ""; }
      var ifcClass = CLASS_NAMES[typeName.toUpperCase()] || typeName;
      var isFastener = ifcClass === "IfcMechanicalFastener" || ifcClass === "IfcFastener";
      var properties = readProperties(api, modelID, propertyIndex.get(expressID), propertyCache);
      var category = isFastener ? classifyFastener(properties) : null;
      if (ifcClass === "IfcFastener" && category === "UNKNOWN_MECHANICAL_FASTENER") category = "PHYSICAL_FASTENER";
      if (category === "MANUFACTURING_VOID") {
        // Tekla models bolt holes as solid "LOCH" fasteners.  The phone cannot drill them, and a
        // solid plug where the real part has a hole is worse than no hole at all.
        skippedVoids += 1;
        return;
      }

      var buckets = new Map();
      var geometries = mesh.geometries;
      for (var g = 0; g < geometries.size(); g++) {
        var placed = geometries.get(g);
        var geometry = api.GetGeometry(modelID, placed.geometryExpressID);
        var vertices = api.GetVertexArray(geometry.GetVertexData(), geometry.GetVertexDataSize());
        var indices = api.GetIndexArray(geometry.GetIndexData(), geometry.GetIndexDataSize());
        var m = placed.flatTransformation;
        triangleCount += indices.length / 3;
        if (triangleCount > maximumTriangles) {
          failure = "Die IFC-Datei hat mehr als " + maximumTriangles + " Dreiecke. Bitte ein Bauteil oder eine " +
            "Baugruppe pro Datei exportieren, damit sie auf dem Telefon verwendet werden kann.";
          geometry.delete();
          return;
        }
        var key = colourKey(placed.color);
        if (!materialIndex.has(key)) {
          materialIndex.set(key, materials.length);
          materials.push(placed.color);
        }
        if (!buckets.has(key)) buckets.set(key, { material: materialIndex.get(key), positions: [], normals: [], indices: [], count: 0 });
        var bucket = buckets.get(key);
        var determinant =
          m[0] * (m[5] * m[10] - m[6] * m[9]) - m[4] * (m[1] * m[10] - m[2] * m[9]) + m[8] * (m[1] * m[6] - m[2] * m[5]);
        var vertexCount = vertices.length / 6;
        var positions = new Float32Array(vertexCount * 3);
        var normals = new Float32Array(vertexCount * 3);
        for (var v = 0; v < vertexCount; v++) {
          var x = vertices[v * 6], y = vertices[v * 6 + 1], z = vertices[v * 6 + 2];
          var nx = vertices[v * 6 + 3], ny = vertices[v * 6 + 4], nz = vertices[v * 6 + 5];
          positions[v * 3] = m[0] * x + m[4] * y + m[8] * z + m[12];
          positions[v * 3 + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
          positions[v * 3 + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
          var wx = m[0] * nx + m[4] * ny + m[8] * nz;
          var wy = m[1] * nx + m[5] * ny + m[9] * nz;
          var wz = m[2] * nx + m[6] * ny + m[10] * nz;
          var length = Math.sqrt(wx * wx + wy * wy + wz * wz) || 1;
          var sign = determinant < 0 ? -1 : 1;
          normals[v * 3] = sign * wx / length;
          normals[v * 3 + 1] = sign * wy / length;
          normals[v * 3 + 2] = sign * wz / length;
        }
        var shifted = new Uint32Array(indices.length);
        for (var i = 0; i < indices.length; i += 3) {
          // A mirrored placement turns every triangle inside out.
          shifted[i] = indices[i] + bucket.count;
          shifted[i + 1] = indices[determinant < 0 ? i + 2 : i + 1] + bucket.count;
          shifted[i + 2] = indices[determinant < 0 ? i + 1 : i + 2] + bucket.count;
        }
        bucket.positions.push(positions);
        bucket.normals.push(normals);
        bucket.indices.push(shifted);
        bucket.count += vertexCount;
        geometry.delete();
      }
      if (!buckets.size) return;
      products.push({
        expressID: expressID,
        globalId: text(line.GlobalId) || ("express-" + expressID),
        ifcClass: ifcClass,
        name: text(line.Name),
        objectType: text(line.ObjectType),
        description: text(line.Description),
        tag: text(line.Tag),
        category: category,
        properties: properties,
        buckets: Array.from(buckets.values()),
      });
    });
    if (failure) throw new Error(failure);
    if (!products.length) throw new Error("Die IFC-Datei enthält keine darstellbare Geometrie.");

    // Recentre: Tekla writes site coordinates, and a part hundreds of metres from the origin
    // loses float precision in the renderer and in the AR pose.
    var min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity];
    products.forEach(function (product) {
      product.buckets.forEach(function (bucket) {
        bucket.positions.forEach(function (positions) {
          for (var i = 0; i < positions.length; i += 3) {
            for (var axis = 0; axis < 3; axis++) {
              var value = positions[i + axis];
              if (value < min[axis]) min[axis] = value;
              if (value > max[axis]) max[axis] = value;
            }
          }
        });
      });
    });
    var centre = [0, 1, 2].map(function (axis) { return (min[axis] + max[axis]) / 2; });

    var chunks = [];
    var byteLength = 0;
    function addChunk(typed) {
      var bytes = new Uint8Array(typed.buffer, typed.byteOffset, typed.byteLength);
      var offset = byteLength;
      chunks.push(bytes);
      byteLength += bytes.byteLength;
      var padding = (4 - (byteLength % 4)) % 4;
      if (padding) { chunks.push(new Uint8Array(padding)); byteLength += padding; }
      return offset;
    }
    function merge(arrays, Type) {
      var total = arrays.reduce(function (sum, array) { return sum + array.length; }, 0);
      var result = new Type(total);
      var offset = 0;
      arrays.forEach(function (array) { result.set(array, offset); offset += array.length; });
      return result;
    }

    var gltf = {
      asset: { version: "2.0", generator: "QS Taller XR IFC converter (web-ifc)" },
      scene: 0,
      scenes: [{ nodes: [] }],
      nodes: [], meshes: [], accessors: [], bufferViews: [], buffers: [],
      materials: materials.map(function (colour) {
        var material = {
          pbrMetallicRoughness: {
            baseColorFactor: [colour.x, colour.y, colour.z, colour.w],
            metallicFactor: 0.25,
            roughnessFactor: 0.6,
          },
          doubleSided: true,
        };
        if (colour.w < 0.999) material.alphaMode = "BLEND";
        return material;
      }),
    };

    var parts = [];
    var usedNames = new Set();
    products.forEach(function (product) {
      var partMin = [Infinity, Infinity, Infinity], partMax = [-Infinity, -Infinity, -Infinity];
      var primitives = product.buckets.map(function (bucket) {
        var positions = merge(bucket.positions, Float32Array);
        var normals = merge(bucket.normals, Float32Array);
        var indices = merge(bucket.indices, Uint32Array);
        var pMin = [Infinity, Infinity, Infinity], pMax = [-Infinity, -Infinity, -Infinity];
        for (var i = 0; i < positions.length; i += 3) {
          for (var axis = 0; axis < 3; axis++) {
            var value = positions[i + axis] - centre[axis];
            positions[i + axis] = value;
            if (value < pMin[axis]) pMin[axis] = value;
            if (value > pMax[axis]) pMax[axis] = value;
          }
        }
        for (var a = 0; a < 3; a++) {
          partMin[a] = Math.min(partMin[a], pMin[a]);
          partMax[a] = Math.max(partMax[a], pMax[a]);
        }
        var views = [[positions, 34962], [normals, 34962], [indices, 34963]].map(function (entry) {
          gltf.bufferViews.push({ buffer: 0, byteOffset: addChunk(entry[0]), byteLength: entry[0].byteLength, target: entry[1] });
          return gltf.bufferViews.length - 1;
        });
        gltf.accessors.push({ bufferView: views[0], componentType: 5126, count: positions.length / 3, type: "VEC3", min: pMin, max: pMax });
        gltf.accessors.push({ bufferView: views[1], componentType: 5126, count: normals.length / 3, type: "VEC3" });
        gltf.accessors.push({ bufferView: views[2], componentType: 5125, count: indices.length, type: "SCALAR" });
        var last = gltf.accessors.length - 1;
        return { attributes: { POSITION: last - 2, NORMAL: last - 1 }, indices: last, material: bucket.material, mode: 4 };
      });
      var nodeName = "part:" + product.globalId;
      while (usedNames.has(nodeName)) nodeName += "_";
      usedNames.add(nodeName);
      gltf.meshes.push({ name: nodeName, primitives: primitives });
      gltf.nodes.push({ name: nodeName, mesh: gltf.meshes.length - 1 });
      gltf.scenes[0].nodes.push(gltf.nodes.length - 1);

      var kind = product.category === "FABRICATION_MARK" ? "FABRICATION_MARK"
        : product.category ? "BOLT_GROUP"
        : product.ifcClass === "IfcPlate" ? "PLATE"
        : "PART";
      var properties = { ifcClass: product.ifcClass };
      if (product.name) properties.name = product.name;
      if (product.objectType) properties.objectType = product.objectType;
      if (product.description) properties.description = product.description;
      if (product.category) properties.fastenerCategory = product.category;
      ["PROFILE", "MATERIAL", "GRADE", "PHASE", "NAME_FULL", "BOLT_STANDARD", "DIAMETER", "LENGTH", "WEIGHT",
        "Assembly position number"].forEach(function (name) {
        var value = property(product.properties, [name]);
        if (value !== null && !(name in properties)) properties[name] = value;
      });
      parts.push({
        guid: product.globalId,
        // Tekla tags bolts with an internal ID, which is noise as a label.
        positionNumber: property(product.properties, ["Part position number", "PART_POS"]) ||
          (product.category ? "" : product.tag) || "",
        kind: kind,
        gltfNodeName: nodeName,
        boundsAssemblyM: { min: partMin, max: partMax },
        inspectable: kind === "PART" || kind === "PLATE",
        properties: properties,
      });
    });

    // The main part of a Baugruppe is not flagged in IFC; the largest profile is the usual one.
    var mainPart = null, mainSize = -1;
    parts.forEach(function (part) {
      if (part.kind !== "PART") return;
      var b = part.boundsAssemblyM;
      var size = Math.max(b.max[0] - b.min[0], b.max[1] - b.min[1], b.max[2] - b.min[2]);
      if (size > mainSize) { mainSize = size; mainPart = part; }
    });
    if (mainPart) mainPart.kind = "MAIN_PART";

    var assemblies = vectorToArray(api.GetLineIDsWithType(modelID, WebIFC.IFCELEMENTASSEMBLY)).map(function (id) {
      try {
        var line = api.GetLine(modelID, id, false);
        var properties = readProperties(api, modelID, propertyIndex.get(id), propertyCache);
        return {
          guid: text(line.GlobalId),
          name: text(line.Name),
          positionNumber: property(properties, ["Assembly position number", "ASSEMBLY_POS"]) || text(line.Tag),
        };
      } catch (error) { return null; }
    }).filter(Boolean);

    gltf.buffers.push({ byteLength: byteLength });
    var json = new TextEncoder().encode(JSON.stringify(gltf));
    var jsonPadding = (4 - (json.byteLength % 4)) % 4;
    var total = 12 + 8 + json.byteLength + jsonPadding + 8 + byteLength;
    var glb = new Uint8Array(total);
    var view = new DataView(glb.buffer);
    view.setUint32(0, 0x46546c67, true);
    view.setUint32(4, 2, true);
    view.setUint32(8, total, true);
    view.setUint32(12, json.byteLength + jsonPadding, true);
    view.setUint32(16, 0x4e4f534a, true);
    glb.set(json, 20);
    for (var p = 0; p < jsonPadding; p++) glb[20 + json.byteLength + p] = 0x20;
    var binStart = 20 + json.byteLength + jsonPadding;
    view.setUint32(binStart, byteLength, true);
    view.setUint32(binStart + 4, 0x004e4942, true);
    var cursor = binStart + 8;
    chunks.forEach(function (chunk) { glb.set(chunk, cursor); cursor += chunk.byteLength; });

    return {
      glb: glb,
      parts: parts,
      assemblies: assemblies,
      stats: {
        productCount: parts.length,
        triangleCount: triangleCount,
        skippedManufacturingVoids: skippedVoids,
        sizeM: [max[0] - min[0], max[1] - min[1], max[2] - min[2]],
      },
    };
  }

  return { convert: convert, classifyFastener: classifyFastener };
});
