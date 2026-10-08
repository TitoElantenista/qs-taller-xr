package com.qs.tallerxr.importer

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

class IfcConversionException(message: String) : Exception(message)

/**
 * Converts one IFC file to GLB on the phone, offline.
 *
 * IFC describes parts as recipes (a profile extruded along a length, minus cuts and copes), which
 * a geometry kernel has to evaluate before anything can be drawn.  The kernel is web-ifc, a
 * WebAssembly build that runs in a WebView created for the job and destroyed after it, so the
 * memory of a large conversion is returned in full.  Every request of that page is answered from
 * the APK assets or from the file being prepared; the network is never used.
 */
class IfcConverter(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    class Result(val parts: IfcPartsDocument, val statistics: String)

    suspend fun convert(
        input: File,
        outputGlb: File,
        onStage: (String) -> Unit = {},
    ): Result = mutex.withLock {
        val job = Job(input, outputGlb, onStage)
        var webView: WebView? = null
        try {
            withTimeout(TIMEOUT_MILLIS) {
                webView = withContext(Dispatchers.Main.immediate) { start(job) }
                job.completion.await()
            }
            Result(
                parts = runCatching { json.decodeFromString<IfcPartsDocument>(job.partsJson) }
                    .getOrElse { throw IfcConversionException("Die IFC-Verarbeitung hat eine unlesbare Bauteilliste geliefert.") },
                statistics = job.statisticsJson,
            )
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw IfcConversionException(
                "Das Einlesen der IFC-Datei hat länger als ${TIMEOUT_MILLIS / 60_000} Minuten gedauert. Die Datei ist " +
                    "zu groß für das Telefon: bitte ein Bauteil oder eine Baugruppe pro Datei exportieren.",
            )
        } finally {
            job.closeOutput()
            val view = webView
            if (view != null) {
                withContext(Dispatchers.Main.immediate + kotlinx.coroutines.NonCancellable) {
                    runCatching {
                        view.stopLoading()
                        view.removeJavascriptInterface(BRIDGE)
                        view.destroy()
                    }
                }
            }
        }
    }

    private inner class Job(val input: File, val outputGlb: File, val onStage: (String) -> Unit) {
        val completion = CompletableDeferred<Unit>()
        var partsJson = ""
        var statisticsJson = ""
        private var expectedBytes = -1L
        private var writtenBytes = 0L
        private var output: OutputStream? = null

        fun fail(message: String) {
            completion.completeExceptionally(IfcConversionException(message))
        }

        @Synchronized
        fun closeOutput() {
            runCatching { output?.close() }
            output = null
        }

        // The callbacks below arrive on the WebView's JavaScript bridge thread.
        fun stage(message: String) = onStage(message)

        @Synchronized
        fun begin(parts: String, statistics: String, glbBytes: Double) {
            partsJson = parts
            statisticsJson = statistics
            expectedBytes = glbBytes.toLong()
            output = outputGlb.outputStream().buffered()
        }

        @Synchronized
        fun chunk(base64: String) {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            writtenBytes += bytes.size
            runCatching { output?.write(bytes) }.onFailure { fail("Das Modell konnte nicht geschrieben werden: ${it.message}") }
        }

        fun finish() {
            closeOutput()
            if (expectedBytes <= 0L || writtenBytes != expectedBytes) {
                fail("Das Einlesen der IFC-Datei blieb unvollständig ($writtenBytes von $expectedBytes Bytes).")
            } else {
                completion.complete(Unit)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun start(job: Job): WebView {
        val view = try {
            WebView(appContext)
        } catch (error: Throwable) {
            throw IfcConversionException(
                "Android System WebView ist nicht verfügbar; es wird zum Lesen von IFC-Dateien benötigt.",
            )
        }
        view.settings.javaScriptEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.blockNetworkLoads = true
        view.addJavascriptInterface(JobBridge(job), BRIDGE)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val url = request.url
                if (url.host != HOST) return notFound()
                return when (val path = url.path.orEmpty()) {
                    "/ifc/input.ifc" -> runCatching {
                        WebResourceResponse("application/octet-stream", null, job.input.inputStream())
                    }.getOrElse { notFound() }
                    else -> ASSETS[path]?.let { mime ->
                        runCatching {
                            WebResourceResponse(mime, null, appContext.assets.open(path.removePrefix("/")))
                        }.getOrNull()
                    } ?: notFound()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                job.fail(
                    "Der IFC-Verarbeitung ging der Arbeitsspeicher aus. Die Datei ist zu groß für das " +
                        "Telefon: bitte ein Bauteil oder eine Baugruppe pro Datei exportieren.",
                )
                // Handled: without this Android kills the whole app together with the renderer.
                return true
            }
        }
        view.onResume()
        view.resumeTimers()
        view.loadUrl("https://$HOST/ifc/converter.html")
        return view
    }

    /** The only object JavaScript can reach; it exposes exactly the five job callbacks. */
    private class JobBridge(private val job: Job) {
        @JavascriptInterface
        fun stage(message: String) = job.stage(message)

        @JavascriptInterface
        fun begin(parts: String, statistics: String, glbBytes: Double) = job.begin(parts, statistics, glbBytes)

        @JavascriptInterface
        fun chunk(base64: String) = job.chunk(base64)

        @JavascriptInterface
        fun finish() = job.finish()

        @JavascriptInterface
        fun fail(message: String) = job.fail(message)
    }

    private fun notFound() = WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )

    private companion object {
        const val BRIDGE = "QsBridge"
        // Reserved by Google for in-app content; it never resolves, and every request is
        // intercepted before it could.
        const val HOST = "appassets.androidplatform.net"
        const val TIMEOUT_MILLIS = 6L * 60L * 1_000L
        val ASSETS = mapOf(
            "/ifc/converter.html" to "text/html",
            "/ifc/web-ifc-api-iife.js" to "text/javascript",
            "/ifc/qs-ifc-converter.js" to "text/javascript",
            "/ifc/web-ifc.wasm" to "application/wasm",
        )
    }
}
