package com.eggriderng.logger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.eggriderng.logger.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val selected = HashSet<String>()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = neededPermissions().all { result[it] == true || hasPerm(it) }
        if (granted) startService() else b.status.text = "Berechtigungen fehlen – bitte in den App-Einstellungen erlauben"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnStart.setOnClickListener {
            if (BleService.running.value) stopService() else ensurePermsThenStart()
        }
        b.chkAll.setOnCheckedChangeListener { _, checked ->
            selected.clear()
            if (checked) tripFiles().forEach { selected.add(it.name) }
            refreshTrips()
        }
        b.btnShare.setOnClickListener { shareSelected() }
        b.btnDelete.setOnClickListener { deleteSelected() }

        collectState()
        refreshTrips()
    }

    override fun onResume() { super.onResume(); refreshTrips() }

    private fun collectState() {
        lifecycleScope.launch { BleService.status.collect { b.status.text = it } }
        lifecycleScope.launch {
            BleService.running.collect { running ->
                b.btnStart.text = if (running) "Aufzeichnung stoppen" else "Verbinden & aufzeichnen"
                if (!running) { b.speed.text = "–"; b.values.text = "" }
                refreshTrips()
            }
        }
        lifecycleScope.launch {
            BleService.live.collect { l ->
                if (l == null) return@collect
                b.speed.text = Math.round(l.speed).toString()
                val power = Math.round(l.voltage * l.current)
                val assist = Protocol.displayAssist(l.assistRaw, BleService.assistType)
                val mode = if (l.mode != 0) "Offroad" else "Road"
                b.values.text = buildString {
                    append("Akku: ").append(l.battery).append(" %\n")
                    append("Leistung: ").append(power).append(" W\n")
                    append("Spannung: ").append(String.format(Locale.GERMAN, "%.1f", l.voltage)).append(" V\n")
                    append("Trittfrequenz: ").append(l.cadence).append(" rpm\n")
                    append("Unterstützung: ").append(assist).append(" ").append(mode).append('\n')
                    append("Licht: ").append(if (l.light != 0) "an" else "aus")
                }
            }
        }
    }

    // ---------- Service ----------
    private fun ensurePermsThenStart() {
        val missing = neededPermissions().filterNot { hasPerm(it) }
        if (missing.isEmpty()) startService() else permLauncher.launch(missing.toTypedArray())
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

    private fun startService() {
        val i = Intent(this, BleService::class.java).setAction(BleService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopService() {
        val i = Intent(this, BleService::class.java).setAction(BleService.ACTION_STOP)
        startService(i)
    }

    // ---------- Fahrten ----------
    private fun tripDir() = File(getExternalFilesDir(null), "trips").apply { mkdirs() }
    private fun tripFiles(): List<File> =
        tripDir().listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun refreshTrips() {
        val container: LinearLayout = b.trips
        container.removeAllViews()
        val files = tripFiles()
        selected.retainAll(files.map { it.name }.toSet())
        val dfOut = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN)
        if (files.isEmpty()) {
            val tv = TextView(this); tv.text = "noch keine Fahrten"; tv.alpha = 0.6f
            container.addView(tv)
        }
        for (f in files) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 16) }
            val cb = CheckBox(this).apply {
                isChecked = selected.contains(f.name)
                setOnCheckedChangeListener { _, c -> if (c) selected.add(f.name) else selected.remove(f.name); updateActionButtons() }
            }
            val tv = TextView(this).apply {
                val kb = Math.max(1, f.length() / 1024)
                text = dfOut.format(java.util.Date(f.lastModified())) + "  ·  " + kb + " KB"
                textSize = 16f
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = lp
                setPadding(12, 0, 0, 0)
            }
            row.addView(cb); row.addView(tv)
            container.addView(row)
        }
        updateActionButtons()
    }

    private fun updateActionButtons() {
        val n = selected.size
        b.btnShare.isEnabled = n > 0
        b.btnDelete.isEnabled = n > 0
        b.btnShare.text = if (n > 1) "Teilen ($n)" else "Teilen"
        b.btnDelete.text = if (n > 1) "Löschen ($n)" else "Löschen"
        b.chkAll.setOnCheckedChangeListener(null)
        val files = tripFiles()
        b.chkAll.isChecked = files.isNotEmpty() && n == files.size
        b.chkAll.setOnCheckedChangeListener { _, checked ->
            selected.clear()
            if (checked) files.forEach { selected.add(it.name) }
            refreshTrips()
        }
    }

    private fun selectedFiles(): List<File> = tripFiles().filter { selected.contains(it.name) }

    private fun shareSelected() {
        val files = selectedFiles()
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

    private fun deleteSelected() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(if (files.size == 1) "Ausgewählte Fahrt löschen?" else "${files.size} Fahrten löschen?")
            .setPositiveButton("Löschen") { _, _ ->
                files.forEach { try { it.delete() } catch (_: Exception) {} }
                selected.clear(); refreshTrips()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
