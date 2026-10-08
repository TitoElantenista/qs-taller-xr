const fs = require("fs");
const path = require("path");
const WebIFC = require(process.env.WEB_IFC_NODE || "web-ifc");
const converter = require(path.join(__dirname, "../../app/src/main/assets/ifc/qs-ifc-converter.js"));
(async () => {
  const api = new WebIFC.IfcAPI();
  await api.Init();
  api.SetLogLevel(WebIFC.LogLevel.LOG_LEVEL_OFF);
  for (const file of process.argv.slice(2)) {
    const t0 = Date.now();
    const id = api.OpenModel(new Uint8Array(fs.readFileSync(file)), { COORDINATE_TO_ORIGIN: false });
    try {
      const result = converter.convert(api, WebIFC, id, {});
      const out = path.basename(file).replace(/\.ifc$/i, "");
      fs.writeFileSync(out + ".glb", result.glb);
      fs.writeFileSync(out + ".parts.json", JSON.stringify({ parts: result.parts, assemblies: result.assemblies }, null, 1));
      const kinds = {};
      result.parts.forEach(p => kinds[p.kind] = (kinds[p.kind] || 0) + 1);
      console.log(path.basename(file), (Date.now() - t0) + " ms", JSON.stringify(result.stats), JSON.stringify(kinds), JSON.stringify(result.assemblies), "glb", result.glb.byteLength);
    } catch (e) { console.log(path.basename(file), "ERROR", e.message); }
    api.CloseModel(id);
  }
})();
