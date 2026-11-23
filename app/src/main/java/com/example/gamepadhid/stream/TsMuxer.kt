package com.example.gamepadhid.stream

import java.io.ByteArrayOutputStream

/**
 * Empaquetador MPEG-TS mínimo para H.264 (solo vídeo).
 * Cada fotograma sale como: [PAT + PMT si es clave] + PES (con PTS) troceado en paquetes de 188 bytes,
 * con PCR en el primer paquete y relleno en el último. Cada fotograma lleva un AUD delante.
 * No es seguro entre hilos: se usa solo desde el hilo del codificador.
 */
class TsMuxer {

    private var ccPat = 0
    private var ccPmt = 0
    private var ccVideo = 0

    /** Devuelve los paquetes TS del fotograma. [es] es H.264 en formato Annex B (con SPS/PPS si es clave). */
    fun mux(es: ByteArray, ptsUs: Long, keyFrame: Boolean): ByteArray {
        val out = ByteArrayOutputStream(es.size + es.size / 40 + 900)
        if (keyFrame) {
            writePsi(out, PAT_PID, ccPat, patSection()); ccPat = (ccPat + 1) and 0x0F
            writePsi(out, PMT_PID, ccPmt, pmtSection()); ccPmt = (ccPmt + 1) and 0x0F
        }
        val pts = ptsUs * 9 / 100 + BASE_90K // reloj de 90 kHz, con 1 s de margen para que el PCR nunca sea negativo
        val pes = buildPes(es, pts)
        packetize(out, pes, pts, keyFrame)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ PES y paquetes

    private fun buildPes(es: ByteArray, pts: Long): ByteArray {
        val aud = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) // access unit delimiter
        val pes = ByteArray(14 + aud.size + es.size)
        pes[0] = 0; pes[1] = 0; pes[2] = 1
        pes[3] = 0xE0.toByte()            // stream_id de vídeo
        pes[4] = 0; pes[5] = 0            // longitud 0 = sin límite (válido para vídeo)
        pes[6] = 0x80.toByte()
        pes[7] = 0x80.toByte()            // solo PTS
        pes[8] = 5                        // longitud de la cabecera opcional
        pes[9] = (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte()
        pes[10] = ((pts shr 22) and 0xFF).toByte()
        pes[11] = (((pts shr 14) and 0xFE) or 1).toByte()
        pes[12] = ((pts shr 7) and 0xFF).toByte()
        pes[13] = (((pts shl 1) and 0xFE) or 1).toByte()
        System.arraycopy(aud, 0, pes, 14, aud.size)
        System.arraycopy(es, 0, pes, 14 + aud.size, es.size)
        return pes
    }

    private fun packetize(out: ByteArrayOutputStream, pes: ByteArray, pts: Long, key: Boolean) {
        val pcr = (pts - 4500).coerceAtLeast(0) // 50 ms antes del PTS
        var off = 0
        var first = true
        while (off < pes.size) {
            val remaining = pes.size - off
            val afBase = if (first) 8 else 0 // longitud + flags + 6 de PCR
            val n = minOf(remaining, 184 - afBase)
            val afTotal = 184 - n            // bytes de adaptación (incluye el de longitud)
            val pkt = ByteArray(188)
            pkt[0] = 0x47
            pkt[1] = ((if (first) 0x40 else 0) or (VIDEO_PID shr 8)).toByte()
            pkt[2] = (VIDEO_PID and 0xFF).toByte()
            pkt[3] = ((if (afTotal == 0) 0x10 else 0x30) or ccVideo).toByte()
            ccVideo = (ccVideo + 1) and 0x0F
            var p = 4
            if (afTotal > 0) {
                pkt[p++] = (afTotal - 1).toByte()
                if (afTotal > 1) {
                    var flags = 0
                    if (first) flags = flags or 0x10
                    if (first && key) flags = flags or 0x40 // random access
                    pkt[p++] = flags.toByte()
                    if (first) {
                        pkt[p++] = ((pcr shr 25) and 0xFF).toByte()
                        pkt[p++] = ((pcr shr 17) and 0xFF).toByte()
                        pkt[p++] = ((pcr shr 9) and 0xFF).toByte()
                        pkt[p++] = ((pcr shr 1) and 0xFF).toByte()
                        pkt[p++] = (((pcr and 1L) shl 7).toInt() or 0x7E).toByte()
                        pkt[p++] = 0
                    }
                    while (p < 4 + afTotal) pkt[p++] = 0xFF.toByte()
                }
            }
            System.arraycopy(pes, off, pkt, 4 + afTotal, n)
            out.write(pkt)
            off += n
            first = false
        }
    }

    // ------------------------------------------------------------------ PSI (PAT / PMT)

    private fun patSection(): ByteArray {
        val body = byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D,           // table_id, longitud 13
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, // tsid, versión, sección 0/0
            0x00, 0x01,                           // programa 1
            (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte()
        )
        return body + crc32(body)
    }

    private fun pmtSection(): ByteArray {
        val body = byteArrayOf(
            0x02, 0xB0.toByte(), 0x12,            // table_id, longitud 18
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(), // PCR PID
            0xF0.toByte(), 0x00,                  // program_info_length = 0
            0x1B,                                 // H.264
            (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00                   // ES_info_length = 0
        )
        return body + crc32(body)
    }

    private fun writePsi(out: ByteArrayOutputStream, pid: Int, cc: Int, section: ByteArray) {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or (pid shr 8)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or cc).toByte()
        pkt[4] = 0 // pointer_field
        System.arraycopy(section, 0, pkt, 5, section.size)
        out.write(pkt)
    }

    /** CRC-32/MPEG-2: polinomio 0x04C11DB7, inicial 0xFFFFFFFF, sin reflejo ni XOR final. */
    private fun crc32(data: ByteArray): ByteArray {
        var crc = -1
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
    }

    private companion object {
        const val PAT_PID = 0
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val BASE_90K = 90_000L
    }
}
