package com.eggriderng.logger

import java.util.UUID

/**
 * EggRider-C1-Protokoll (an Hardware bestätigt). Entspricht der getesteten Web-App.
 * Alle u16 little-endian, CRC-16/CCITT-FALSE, High-Byte zuerst angehängt.
 */
object Protocol {
    val SERVICE: UUID = UUID.fromString("e7be0001-daae-bbb7-e711-a8dbea57019a")
    val CH_WRITE: UUID = UUID.fromString("e7be0e02-daae-bbb7-e711-a8dbea57019a")
    val CH_NOTIFY: UUID = UUID.fromString("e7be0e03-daae-bbb7-e711-a8dbea57019a")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val LEN_IDENTITY = 14
    const val LEN_SETTINGS = 34
    const val LEN_LIVE = 24

    // App-Anmeldung: 2E 18 <VerMinor> <VerPatch> <AppCode 4 Byte LE> <Modus=1> 00 00
    fun appIdentity(): ByteArray =
        withCrc(byteArrayOf(0x2E, 0x18, 9, 0x1B, 0x44, 0x33, 0x22, 0x11, 1, 0, 0))

    fun readIdentity(): ByteArray = withCrc(byteArrayOf(0x22, 0x12))
    fun readSettings(): ByteArray = withCrc(byteArrayOf(0x22, 0x29))
    fun readLive(): ByteArray = withCrc(byteArrayOf(0x22, 0x3A))

    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var c = 0xFFFF
        for (i in 0 until len) {
            c = c xor ((data[i].toInt() and 0xFF) shl 8)
            for (j in 0 until 8) {
                c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1
                c = c and 0xFFFF
            }
        }
        return c and 0xFFFF
    }

    fun withCrc(cmd: ByteArray): ByteArray {
        val c = crc16(cmd)
        return cmd + byteArrayOf((c shr 8).toByte(), (c and 0xFF).toByte())
    }

    fun crcOk(rx: ByteArray): Boolean {
        if (rx.size < 4) return false
        val c = crc16(rx, rx.size - 2)
        return (rx[rx.size - 2].toInt() and 0xFF) == (c shr 8) &&
                (rx[rx.size - 1].toInt() and 0xFF) == (c and 0xFF)
    }

    private fun u8(d: ByteArray, o: Int) = d[o].toInt() and 0xFF
    private fun u16le(d: ByteArray, o: Int) = u8(d, o) or (u8(d, o + 1) shl 8)

    /** Live-Status (22 3A). Nutzdaten beginnen nach dem 2-Byte-Kopf. */
    fun parseLive(rx: ByteArray): Live {
        val d = rx.copyOfRange(2, rx.size - 2)
        return Live(
            voltage = u16le(d, 0) * 0.01,
            speed = u16le(d, 2) * 0.01,
            current = u16le(d, 4) * 0.1,
            battery = u8(d, 6),
            cadence = u8(d, 7),
            torque = u8(d, 8),
            heartRaw = u8(d, 9),
            tDisplayRaw = u8(d, 10) - 50,
            tMotorRaw = u8(d, 11) - 50,
            tControllerRaw = u8(d, 12) - 50,
            tBatteryRaw = u8(d, 13) - 50,
            error = u8(d, 14),
            light = (u8(d, 15) shr 7) and 1,
            lock = (u8(d, 15) shr 4) and 1,
            assistRaw = (u8(d, 16) shr 4) and 0x0F,
            mode = u8(d, 16) and 0x0F
        )
    }

    /** Display-Einstellungen (22 29): nur die Werte, die wir brauchen. */
    fun parseSettings(rx: ByteArray): Settings {
        val d = rx.copyOfRange(2, rx.size - 2)
        return Settings(
            assistType = (u8(d, 2)) and 0x0F,           // 0=9, 3=3, 5=5 Stufen
            maxSpeedRoad = u8(d, 6),
            maxSpeedOffroad = u8(d, 7),
            wheelMm = u16le(d, 4),
            protocol = u8(d, 8)
        )
    }

    // Rohwert 0..9 → angezeigte Stufe je nach Display-Einstellung
    fun displayAssist(raw: Int, assistType: Int): Int = when (assistType) {
        3 -> mapOf(0 to 0, 1 to 1, 5 to 2, 9 to 3)[raw] ?: raw
        5 -> mapOf(0 to 0, 1 to 1, 3 to 2, 5 to 3, 7 to 4, 9 to 5)[raw] ?: raw
        else -> raw
    }

    data class Live(
        val voltage: Double, val speed: Double, val current: Double,
        val battery: Int, val cadence: Int, val torque: Int, val heartRaw: Int,
        val tDisplayRaw: Int, val tMotorRaw: Int, val tControllerRaw: Int, val tBatteryRaw: Int,
        val error: Int, val light: Int, val lock: Int, val assistRaw: Int, val mode: Int
    )

    data class Settings(
        val assistType: Int, val maxSpeedRoad: Int, val maxSpeedOffroad: Int,
        val wheelMm: Int, val protocol: Int
    )
}
