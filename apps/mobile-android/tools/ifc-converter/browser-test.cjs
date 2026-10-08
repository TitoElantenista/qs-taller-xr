// Serves the APK assets exactly as IfcConverter.shouldInterceptRequest does and plays the role of
// the QsBridge object, so the real converter.html runs in a real Chromium.
const http = require("http"), fs = require("fs"), path = require("path"), { spawn } = require("child_process");
const assets = path.join(__dirname, "../../app/src/main/assets/ifc");
const input = process.argv[2];
const mime = { ".html": "text/html", ".js": "text/javascript", ".wasm": "application/wasm" };
const shim = `<script>
window.__chunks = []; window.__meta = null;
window.QsBridge = {
  stage: function (m) { fetch("/log?m=" + encodeURIComponent(m)); },
  begin: function (parts, stats, bytes) { window.__meta = { parts: parts, stats: stats, bytes: bytes }; },
  chunk: function (b64) { window.__chunks.push(b64); },
  finish: function () { fetch("/result", { method: "POST", body: JSON.stringify({ meta: window.__meta, chunks: window.__chunks }) }); },
  fail: function (m) { fetch("/result", { method: "POST", body: JSON.stringify({ error: m }) }); }
};
</script>`;
let chrome;
const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  if (url.pathname === "/log") { console.log("stage:", url.searchParams.get("m")); res.end(); return; }
  if (url.pathname === "/result") {
    let body = ""; req.on("data", c => body += c); req.on("end", () => {
      res.end();
      const r = JSON.parse(body);
      if (r.error) { console.log("FAIL:", r.error); }
      else {
        const glb = Buffer.concat(r.chunks.map(c => Buffer.from(c, "base64")));
        const parts = JSON.parse(r.meta.parts);
        console.log("OK chunks", r.chunks.length, "glb bytes", glb.length, "declared", r.meta.bytes, "magic", glb.toString("ascii", 0, 4),
          "parts", parts.parts.length, "stats", r.meta.stats);
        fs.writeFileSync("browser-output.glb", glb);
      }
      chrome.kill(); server.close();
    }); return;
  }
  if (url.pathname === "/ifc/input.ifc") { res.writeHead(200, { "Content-Type": "application/octet-stream" }); fs.createReadStream(input).pipe(res); return; }
  const file = path.join(assets, path.basename(url.pathname));
  if (!url.pathname.startsWith("/ifc/") || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { "Content-Type": mime[path.extname(file)] || "application/octet-stream" });
  if (file.endsWith("converter.html")) res.end(fs.readFileSync(file, "utf8").replace("<body>", "<body>" + shim));
  else fs.createReadStream(file).pipe(res);
});
server.listen(0, "127.0.0.1", () => {
  const port = server.address().port;
  chrome = spawn("google-chrome-stable", ["--headless=new", "--no-sandbox", "--disable-gpu", "--user-data-dir=" + path.resolve("chrome-profile"),
    `http://127.0.0.1:${port}/ifc/converter.html`], { stdio: "ignore" });
  setTimeout(() => { console.log("TIMEOUT"); chrome.kill(); server.close(); }, 90000).unref();
});
