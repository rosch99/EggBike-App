package com.eggriderng.logger

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewAssetLoader
import com.eggriderng.logger.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Hülle um die Web-Oberfläche (assets/web/index.html, übernommen aus der Web-App).
 * Bluetooth, GPS und Fahrt-Aufzeichnung laufen in [BleService]; diese Activity reicht
 * Zustand und Rohdaten über window.Native.* an die Seite weiter und nimmt Befehle über
 * window.EggBridge entgegen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    @Volatile private var pageReady = false
    private var lastLogSeq = 0L

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = neededPermissions().all { result[it] == true || hasPerm(it) }
        if (granted) startBle()
        else js("Native.status('err', 'Berechtigungen fehlen', 'Bitte Bluetooth, Standort und Benachrichtigungen in den App-Einstellungen erlauben.', false)")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        b.web.setBackgroundColor(Color.parseColor("#0E1116"))
        b.web.settings.javaScriptEnabled = true
        b.web.settings.domStorageEnabled = true           // localStorage: Hell/Dunkel, Akkutyp
        b.web.settings.allowFileAccess = false
        b.web.webChromeClient = WebChromeClient()          // nötig für confirm() beim Löschen
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        b.web.addJavascriptInterface(Bridge(), "EggBridge")
        b.web.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")

        observeService()
    }

    override fun onResume() {
        super.onResume()
        if (pageReady) js("Native.tripsChanged()")
    }

    // ---------- Seite ← Dienst ----------
    private fun js(code: String) {
        runOnUiThread { if (pageReady) b.web.evaluateJavascript(code, null) }
    }

    private fun q(s: String) = JSONObject.quote(s)

    private fun bytes(a: ByteArray) = a.joinToString(",", "[", "]") { (it.toInt() and 0xFF).toString() }

    private fun pushStatus() {
        val s = BleService.status.value
        js("Native.status(${q(s.kind)}, ${q(s.text)}, ${q(s.sub)}, ${BleService.running.value})")
    }

    private fun pushTrip() {
        js("Native.trip(${BleService.recording.value}, ${BleService.tripStart.value}, ${BleService.tripDist.value})")
    }

    /** Wird aufgerufen, wenn die Seite geladen ist und die Brücke bereit ist. */
    private fun onPageReady() {
        pageReady = true
        lastLogSeq = 0L
        pushStatus()
        pushTrip()
        BleService.settingsRaw.value?.let { js("Native.settings(${bytes(it)})") }
        BleService.liveRaw.value?.let { js("Native.live(${bytes(it)})") }
        BleService.logs.replayCache.forEach { pushLog(it) }
        js("Native.tripsChanged()")
    }

    private fun pushLog(l: BleService.Companion.LogLine) {
        if (l.seq <= lastLogSeq) return
        lastLogSeq = l.seq
        js("Native.log(${q(l.text)})")
    }

    private fun observeService() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(BleService.status, BleService.running) { s, r -> s to r }
                        .collect { if (pageReady) pushStatus() }
                }
                launch {
                    BleService.liveRaw.collect { raw -> if (pageReady && raw != null) js("Native.live(${bytes(raw)})") }
                }
                launch {
                    BleService.settingsRaw.collect { raw -> if (pageReady && raw != null) js("Native.settings(${bytes(raw)})") }
                }
                launch {
                    BleService.logs.collect { if (pageReady) pushLog(it) }
                }
                launch {
                    // Fahrt gestoppt → Liste neu laden (die Datei ist jetzt fertig)
                    BleService.recording.drop(1).filter { !it }
                        .collect { if (pageReady) js("Native.tripsChanged()") }
                }
                launch {
                    while (true) {                   // Fahrtdauer und -strecke jede Sekunde aktualisieren
                        if (pageReady) pushTrip()
                        delay(1000)
                    }
                }
            }
        }
    }

    // ---------- Seite → Dienst ----------
    private inner class Bridge {
        @JavascriptInterface fun ready() = runOnUiThread { onPageReady() }
        @JavascriptInterface fun connect() = runOnUiThread { ensurePermsThenStart() }
        @JavascriptInterface fun disconnect() = runOnUiThread { sendToService(BleService.ACTION_STOP) }
        @JavascriptInterface fun tripStart() = runOnUiThread {
            if (BleService.running.value) sendToService(BleService.ACTION_TRIP_START)
        }
        @JavascriptInterface fun tripStop() = runOnUiThread { sendToService(BleService.ACTION_TRIP_STOP) }
        @JavascriptInterface fun setVerbose(on: Boolean) { BleService.verbose = on }
        @JavascriptInterface fun listTrips(): String = tripsJson()
        @JavascriptInterface fun shareTrips(idsJson: String) = runOnUiThread { share(ids(idsJson)) }
        @JavascriptInterface fun deleteTrips(idsJson: String) {
            val keep = BleService.currentFile.value
            filesFor(ids(idsJson)).filter { it.name != keep }.forEach { try { it.delete() } catch (_: Exception) {} }
        }
        @JavascriptInterface fun copy(text: String) = runOnUiThread {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("EggRider Protokoll", text))
        }
        @JavascriptInterface fun setBarColor(color: String, light: Boolean) = runOnUiThread {
            try {
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.parseColor(color)
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = light
            } catch (_: Exception) {}
        }
    }

    private fun ids(json: String): Set<String> = try {
        val a = JSONArray(json); (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (_: Exception) { emptySet() }

    // ---------- Dienst / Berechtigungen ----------
    private fun ensurePermsThenStart() {
        val missing = neededPermissions().filterNot { hasPerm(it) }
        if (missing.isEmpty()) startBle() else permLauncher.launch(missing.toTypedArray())
    }

    private fun neededPermissions(): List<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return p
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startBle() {
        val i = Intent(this, BleService::class.java).setAction(BleService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }

    private fun sendToService(action: String) {
        if (!BleService.running.value) return           // Dienst läuft nicht → nichts zu tun
        startService(Intent(this, BleService::class.java).setAction(action))
    }

    // ---------- Fahrten (CSV-Dateien des Dienstes) ----------
    private fun tripDir() = File(getExternalFilesDir(null), "trips").apply { mkdirs() }

    private fun tripFiles(): List<File> =
        tripDir().listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.toList() ?: emptyList()

    private fun filesFor(ids: Set<String>) = tripFiles().filter { ids.contains(it.name) }

    private data class Summary(val startTs: Long, val durS: Long, val dist: Double, val wh: Double, val avg: Double, val max: Double)
    private val summaryCache = HashMap<String, Pair<Long, Summary>>()   // Name → (Größe, Zusammenfassung)

    /** Liste im Format, das renderTrips() der Web-Oberfläche erwartet. Die laufende Fahrt wird ausgelassen. */
    private fun tripsJson(): String {
        val active = BleService.currentFile.value
        val arr = JSONArray()
        for (f in tripFiles()) {
            if (f.name == active) continue
            val s = summaryOf(f) ?: continue
            arr.put(JSONObject()
                .put("id", f.name)
                .put("startTs", s.startTs)
                .put("summary", JSONObject()
                    .put("durS", s.durS).put("dist", s.dist).put("wh", s.wh)
                    .put("avgSpeed", s.avg).put("maxSpeed", s.max)))
        }
        return arr.toString()
    }

    private fun summaryOf(f: File): Summary? = synchronized(summaryCache) {
        summaryCache[f.name]?.let { if (it.first == f.length()) return it.second }
        val s = try { parseCsv(f) } catch (_: Exception) { null } ?: return null
        summaryCache[f.name] = f.length() to s
        s
    }

    /** CSV-Spalten: 0 Zeit, 1 Tempo, 2 Spannung, 3 Strom, 4 Strecke, 6 Datum, 20 Motorleistung. */
    private fun parseCsv(f: File): Summary? {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        var first = 0L; var last = 0L; var prev = 0L
        var dist = 0.0; var max = 0.0; var wh = 0.0
        f.bufferedReader().useLines { lines ->
            for (line in lines.drop(1)) {
                val c = line.split(';')
                if (c.size < 21) continue
                val t = try { fmt.parse(c[6] + " " + c[0])?.time ?: continue } catch (_: Exception) { continue }
                if (first == 0L) first = t
                val spd = c[1].toDoubleOrNull() ?: 0.0
                if (spd > max) max = spd
                dist = c[4].toDoubleOrNull() ?: dist
                val p = c[20].toDoubleOrNull() ?: 0.0
                if (prev != 0L) wh += p * ((t - prev).coerceIn(0, 5000) / 3600000.0)
                prev = t; last = t
            }
        }
        if (first == 0L) return null
        val durS = (last - first) / 1000
        return Summary(first, durS, dist, wh, if (durS > 0) dist / (durS / 3600.0) else 0.0, max)
    }

    private fun share(ids: Set<String>) {
        val files = filesFor(ids)
        if (files.isEmpty()) return
        val auth = "$packageName.fileprovider"
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(this, auth, it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = "text/csv"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Fahrten senden als …"))
    }

    override fun onDestroy() {
        pageReady = false
        b.web.destroy()
        super.onDestroy()
    }
}
