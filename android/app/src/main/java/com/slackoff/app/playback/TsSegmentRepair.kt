package com.slackoff.app.playback

enum class SegmentKind {
    PLAYLIST,
    TRANSPORT_STREAM,
    WRAPPED_TRANSPORT_STREAM,
    FRAGMENTED_MP4,
    IMAGE,
    UNKNOWN
}

object TsSegmentRepair {
    private const val SYNC_BYTE: Byte = 0x47
    private const val STANDARD_PACKET_SIZE = 188
    private val CANDIDATE_PACKET_SIZES = intArrayOf(188, 192, 204)
    private const val CONFIRM_PACKETS = 4
    private const val MAX_HEADER_SCAN = 64 * 1024

    data class Layout(
        val packetSize: Int,
        val range: IntRange
    )

    fun kindOf(data: ByteArray): SegmentKind {
        if (data.isEmpty()) return SegmentKind.UNKNOWN

        if (data.size >= 7 && String(data, 0, 7) == "#EXTM3U") return SegmentKind.PLAYLIST

        val layout = layoutOf(data)
        if (layout != null) {
            return if (layout.range.first == 0 && layout.range.last >= data.size - layout.packetSize) {
                SegmentKind.TRANSPORT_STREAM
            } else {
                SegmentKind.WRAPPED_TRANSPORT_STREAM
            }
        }

        if (data.size >= 8 && String(data, 4, 4) == "ftyp") return SegmentKind.FRAGMENTED_MP4
        if (isImage(data)) return SegmentKind.IMAGE
        return SegmentKind.UNKNOWN
    }

    fun repairedSegment(data: ByteArray): ByteArray? {
        val layout = layoutOf(data) ?: return null
        if (layout.range.first == 0 && layout.range.last == data.size) return data
        return data.copyOfRange(layout.range.first, layout.range.last)
    }

    fun layoutOf(data: ByteArray): Layout? {
        if (data.size < STANDARD_PACKET_SIZE * 2) return null
        val start = firstSyncStart(data) ?: return null
        val packetSize = packetSizeAt(data, start) ?: return null

        var end = start
        var resyncs = 0
        while (end + packetSize <= data.size) {
            if (data[end] == SYNC_BYTE) {
                end += packetSize
                continue
            }
            if (resyncs < 2) {
                val next = resync(data, end, packetSize)
                if (next != null) {
                    end = next
                    resyncs++
                    continue
                }
            }
            break
        }

        if (end - start < packetSize * 2) return null
        return Layout(packetSize = packetSize, range = start until end)
    }

    private fun firstSyncStart(data: ByteArray): Int? {
        val limit = minOf(data.size - STANDARD_PACKET_SIZE * CONFIRM_PACKETS, MAX_HEADER_SCAN)
        if (limit < 0) return null

        // First pass: look for PAT packet (PID == 0)
        for (i in 0..limit) {
            if (data[i] == SYNC_BYTE && isPAT(data, i) && packetSizeAt(data, i) != null) {
                return i
            }
        }

        // Second pass: any valid sync byte chain
        for (i in 0..limit) {
            if (data[i] == SYNC_BYTE && packetSizeAt(data, i) != null) {
                return i
            }
        }
        return null
    }

    private fun isPAT(data: ByteArray, index: Int): Boolean {
        return data[index] == SYNC_BYTE && pid(data, index) == 0
    }

    private fun pid(data: ByteArray, index: Int): Int {
        if (index + 2 >= data.size) return -1
        return ((data[index + 1].toInt() and 0x1F) shl 8) or (data[index + 2].toInt() and 0xFF)
    }

    private fun packetSizeAt(data: ByteArray, index: Int): Int? {
        for (size in CANDIDATE_PACKET_SIZES) {
            var matched = true
            for (step in 1 until CONFIRM_PACKETS) {
                val at = index + size * step
                if (at >= data.size || data[at] != SYNC_BYTE) {
                    matched = false
                    break
                }
            }
            if (matched) return size
        }
        return null
    }

    private fun resync(data: ByteArray, after: Int, packetSize: Int): Int? {
        val limit = minOf(after + packetSize * 2, data.size - packetSize * CONFIRM_PACKETS)
        for (candidate in (after + 1)..limit) {
            if (data[candidate] == SYNC_BYTE && packetSizeAt(data, candidate) == packetSize) {
                return candidate
            }
        }
        return null
    }

    private fun isImage(data: ByteArray): Boolean {
        if (data.size < 4) return false
        // PNG
        if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()) return true
        // JPEG
        if (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()) return true
        // GIF
        if (data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() && data[3] == 0x38.toByte()) return true
        // BMP
        if (data[0] == 0x42.toByte() && data[1] == 0x4D.toByte()) return true
        // RIFF/WebP
        if (data[0] == 0x52.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte() && data[3] == 0x46.toByte()) return true
        return false
    }
}