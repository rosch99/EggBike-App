package com.eggriderng.logger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class BleService : Service() {

    companion object {
        const val ACTION_START = "com.eggriderng.logger.START"
        const val ACTION_STOP = "com.eggriderng.logger.STOP"
        private const val CHANNEL = "eggrider"
        private const val NOTIF_ID = 1

        // Zustand, den die Oberfläche beobachtet
        val status = MutableStateFlow("Getrennt")
        val live = MutableStateFlow<Protocol.Live?>(null)
        val running = MutableStateFlow(false)
        val startedAt = MutableStateFlow(0L)
        val currentFile = MutableStateFlow<String?>(null)
        var assistType = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var gatt: BluetoothGatt? = null
    private var chWrite: BluetoothGattCharacteristic? = null

    private val rxLock = Any()
    private val rxBuf = ArrayList<Byte>()
    @Volatile private var pending: CompletableDeferred<ByteArray?>? = null
    @Volatile private var needed = 0
    @Volatile private var connected = false
    @Volatile private var wrongCRCs = 0

    private var connDef: CompletableDeferred<Boolean>? = null
    private var discDef: CompletableDeferred<Boolean>? = null
    private var cccdDef: CompletableDeferred<Boolean>? = null

    private val reqMutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null

    // CSV
    private var csv: File? = null
    private var lastLoc: Location? = null
    private val ema = HashMap<String, Double>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        startForegroundNotif("Starte …")
        if (job == null) startSession()
        return START_STICKY
    }

    // ---------- Sitzung ----------
    private fun startSession() {
        running.value = true
        wrongCRCs = 0
        startGps()
        acquireWake()
        job = scope.launch {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) { status.value = "Bluetooth aus"; stopSelf(); return@launch }
            openCsv()
            var tries = 0
            while (isActive && running.value) {
                status.value = if (tries == 0) "Suche Display …" else "Neuverbindung …"
                val dev = findDevice(adapter)
                if (dev == null) { status.value = "Kein Display gefunden"; delay(3000); tries++; continue }
                status.value = "Verbinde …"
                if (!connectAndSetup(dev)) { status.value = "Verbindung fehlgeschlagen"; closeGatt(); delay(2500); tries++; continue }
                status.value = "Verbunden"
                tries = 0
                runComms()                      // läuft bis zum Verbindungsabbruch
                closeGatt()
                if (running.value) { status.value = "Verbindung verloren"; delay(1500) }
            }
        }
    }

    private fun stopSession() {
        running.value = false
        job?.cancel(); job = null
        closeGatt()
        stopGps(); releaseWake()
        live.value = null
        status.value = "Getrennt"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runComms() {
        request(Protocol.appIdentity(), 4, 1500)
        request(Protocol.readIdentity(), Protocol.LEN_IDENTITY, 1500)
        request(Protocol.readSettings(), Protocol.LEN_SETTINGS, 2000)?.let {
            if (Protocol.crcOk(it)) assistType = Protocol.parseSettings(it).assistType
        }
        startedAt.value = System.currentTimeMillis()
        var lastSample = 0L
        var notifT = 0L
        while (scope.isActive && connected && running.value) {
            val rx = request(Protocol.readLive(), Protocol.LEN_LIVE, 1000)
            if (rx != null && rx.size == Protocol.LEN_LIVE && Protocol.crcOk(rx)) {
                val l = Protocol.parseLive(rx)
                live.value = l
                val now = System.currentTimeMillis()
                if (now - lastSample >= 1000) { lastSample = now; writeRow(now, l) }
                if (now - notifT >= 2000) { notifT = now; updateNotif(l) }
            }
            delay(400)
        }
    }

    // ---------- BLE-Aufbau ----------
    private suspend fun findDevice(adapter: BluetoothAdapter): BluetoothDevice? {
        val remembered = getSharedPreferences("eg", MODE_PRIVATE).getString("addr", null)
        if (remembered != null) {
            try { adapter.getRemoteDevice(remembered)?.let { return it } } catch (_: Exception) {}
        }
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice?>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                if (name != null && name.startsWith("EggRider")) {
                    if (!found.isCompleted) found.complete(result.device)
                }
            }
        }
        scanner.startScan(cb)
        val dev = withTimeoutOrNull(15000) { found.await() }
        try { scanner.stopScan(cb) } catch (_: Exception) {}
        if (dev != null) getSharedPreferences("eg", MODE_PRIVATE).edit().putString("addr", dev.address).apply()
        return dev
    }

    private suspend fun connectAndSetup(device: BluetoothDevice): Boolean {
        synchronized(rxLock) { rxBuf.clear() }
        connected = false
        val cd = CompletableDeferred<Boolean>(); connDef = cd
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (withTimeoutOrNull(12000) { cd.await() } != true) return false

        val dd = CompletableDeferred<Boolean>(); discDef = dd
        gatt?.discoverServices()
        if (withTimeoutOrNull(12000) { dd.await() } != true) return false

        val svc = gatt?.getService(Protocol.SERVICE) ?: return false
        chWrite = svc.getCharacteristic(Protocol.CH_WRITE)
        val notify = svc.getCharacteristic(Protocol.CH_NOTIFY) ?: return false
        if (chWrite == null) return false

        gatt?.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(Protocol.CCCD) ?: return false
        val wd = CompletableDeferred<Boolean>(); cccdDef = wd
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION") run { cccd.value = enable; gatt?.writeDescriptor(cccd) }
        }
        return withTimeoutOrNull(8000) { wd.await() } == true
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                connDef?.takeIf { !it.isCompleted }?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                connDef?.takeIf { !it.isCompleted }?.complete(false)
                discDef?.takeIf { !it.isCompleted }?.complete(false)
                cccdDef?.takeIf { !it.isCompleted }?.complete(false)
                pending?.takeIf { !it.isCompleted }?.complete(null)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            discDef?.takeIf { !it.isCompleted }?.complete(statusCode == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int) {
            cccdDef?.takeIf { !it.isCompleted }?.complete(statusCode == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onBytes(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onBytes(value)
        }
    }

    private fun onBytes(value: ByteArray) {
        synchronized(rxLock) {
            for (b in value) rxBuf.add(b)
            val p = pending
            if (p != null && !p.isCompleted && rxBuf.size >= needed) {
                val out = ByteArray(needed)
                for (i in 0 until needed) out[i] = rxBuf[i]
                repeat(needed) { rxBuf.removeAt(0) }
                p.complete(out)
            }
        }
    }

    private suspend fun request(cmd: ByteArray, len: Int, timeout: Long): ByteArray? = reqMutex.withLock {
        if (!connected) return null
        val def = CompletableDeferred<ByteArray?>()
        synchronized(rxLock) { rxBuf.clear(); needed = len; pending = def }
        writeCmd(cmd)
        val rx = withTimeoutOrNull(timeout) { def.await() }
        pending = null
        if (rx != null && !Protocol.crcOk(rx)) wrongCRCs++
        rx
    }

    private fun writeCmd(cmd: ByteArray) {
        val c = chWrite ?: return
        val g = gatt ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, cmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION") run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = cmd
                g.writeCharacteristic(c)
            }
        }
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; chWrite = null; connected = false
    }

    // ---------- CSV ----------
    private fun smooth(key: String, v: Double): Double {
        val a = 0.3
        val prev = ema[key]
        val n = if (prev == null) v else prev + a * (v - prev)
        ema[key] = n
        return n
    }

    private fun openCsv() {
        ema.clear()
        val dir = File(getExternalFilesDir(null), "trips").apply { mkdirs() }
        val name = "eggrider_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".csv"
        val f = File(dir, name)
        f.writeText(
            "Time(HH:mm:ss.fff);Speed(km/h);Voltage(V);Current(A);Distance(km);Altitude(m);Date;SpeedGPS(km/h);" +
            "Latitude;Longitude;AccuracyPosition(m);BatteryPercentage;WrongCRCs;AssistLevel;OffRoadMode;ErrorCode;" +
            "DisplayTemp(C);MotorTemp(C);BatteryTemp(C);ControllerTemp(C);MotorPower(W);PedalCadence(rpm);HumanTorque(Nm);HeartRate(bpm)\n"
        )
        csv = f
        currentFile.value = f.name
        distance = 0.0; lastRowT = 0L
    }

    private var distance = 0.0
    private var lastRowT = 0L

    private fun writeRow(now: Long, l: Protocol.Live) {
        val f = csv ?: return
        val spd = smooth("spd", l.speed); val v = smooth("v", l.voltage); val i = smooth("i", l.current)
        val cad = smooth("cad", l.cadence.toDouble()); val tq = smooth("tq", l.torque.toDouble())
        if (lastRowT != 0L) distance += spd * ((now - lastRowT) / 3600000.0)
        lastRowT = now
        val loc = lastLoc
        val d = Date(now)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(d)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
        val lat = loc?.latitude?.toString() ?: ""
        val lon = loc?.longitude?.toString() ?: ""
        val alt = loc?.altitude ?: 0.0
        val gps = (loc?.speed ?: 0f) * 3.6
        val acc = if (loc != null) String.format(Locale.US, "%.2f", loc.accuracy) else ""
        val row = StringBuilder()
            .append(time).append(';')
            .append(fmt(spd)).append(';').append(fmt(v)).append(';').append(fmt(i)).append(';')
            .append(fmt(distance)).append(';').append(fmt(alt)).append(';').append(date).append(';')
            .append(fmt(gps)).append(';').append(lat).append(';').append(lon).append(';').append(acc).append(';')
            .append(l.battery).append(';').append(wrongCRCs).append(';').append(l.assistRaw).append(';')
            .append(l.mode).append(';').append(l.error).append(';')
            .append(l.tDisplayRaw).append(';').append(l.tMotorRaw).append(';').append(l.tBatteryRaw).append(';').append(l.tControllerRaw).append(';')
            .append(Math.round(v * i)).append(';').append(Math.round(cad)).append(';').append(Math.round(tq)).append(';').append(l.heartRaw)
            .append('\n')
        try { f.appendText(row.toString()) } catch (_: Exception) {}
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.2f", v)

    // ---------- GPS ----------
    // Vollständige Implementierung des Interface (nicht als Lambda) wegen älterer Android-Versionen.
    private val locListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) { lastLoc = location }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startGps() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
            }
        } catch (_: Exception) {}
    }

    private fun stopGps() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locListener)
        } catch (_: Exception) {}
        lastLoc = null
    }

    // ---------- Vordergrund / Benachrichtigung ----------
    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eggrider:log").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun releaseWake() { try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}; wakeLock = null }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aufzeichnung", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun buildNotif(text: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("EggRider Logger")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_bike)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundNotif(text: String) {
        val n = buildNotif(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(l: Protocol.Live) {
        val txt = String.format(Locale.US, "%.0f km/h · %d%% · %.0f W", l.speed, l.battery, l.voltage * l.current)
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(txt)) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running.value = false
        scope.coroutineContext[Job]?.cancel()
        closeGatt(); stopGps(); releaseWake()
        super.onDestroy()
    }
}
