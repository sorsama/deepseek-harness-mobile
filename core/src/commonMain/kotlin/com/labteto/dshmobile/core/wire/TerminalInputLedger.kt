package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.RemoteUplinkWindow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The client half of the duplex terminal that harness 0.2.1-alpha.3 introduced.
 *
 * `terminal/write` and `terminal/resize` were unary calls before; now both ride the uplink of the
 * `terminal/attach` stream as numbered commands, and the host answers each with an `ack` frame on
 * the downlink. Three host rules shape this class, all from
 * `packages/api/terminal-controller/src/client/model.ts` at rc.2:
 *
 * - Sequences start at 1 for each attachment and go up by exactly 1. A gap ends the stream, so a
 *   command is numbered when it is accepted here and never renumbered or dropped afterwards.
 * - The host's per-stream inbox is bounded ([RemoteUplinkWindow]). Encoded frames that have been
 *   sent but not acknowledged must fit in it, or the stream fails with `gateway/uplink-overflow`.
 *   Commands past that point wait here and go out as acks free room.
 * - Raw input bytes waiting for an ack are capped by `maxInputBytes`, and encoded bytes held on
 *   the client by `maxBufferedBytes`. Input past either cap is refused rather than queued, the
 *   same "input full" the web terminal reports.
 *
 * Not thread-safe; the terminal screen drives it from one coroutine context.
 */
class TerminalInputLedger(
    private val maxInputBytes: Int,
    /** Null for a host that does not report it; the window alone then bounds what is held. */
    private val maxBufferedBytes: Int?,
) {
    private class Entry(val sequence: Long, val command: JsonObject, val inputBytes: Int, val frameBytes: Int)

    /** The inbox budget from the attachment's snapshot frame; null while unknown or unbounded. */
    var window: RemoteUplinkWindow? = null

    private var sequence = 0L
    private var acknowledged = 0L
    private val queued = ArrayDeque<Entry>()
    private val pending = LinkedHashMap<Long, Entry>()
    private var inputBytes = 0
    private var bufferedBytes = 0
    private var inFlightBytes = 0

    /** Number of commands sent and not yet acknowledged. */
    val inFlight: Int get() = pending.size

    /** Number of commands accepted but still waiting for room in the window. */
    val waiting: Int get() = queued.size

    /** Accept keyboard input. False means the input would overflow a budget and was refused. */
    fun write(data: String): Boolean {
        if (data.isEmpty()) return true
        val bytes = data.toByteArray(Charsets.UTF_8).size
        if (inputBytes + bytes > maxInputBytes) return false
        return admit(command("write") { put("data", JsonPrimitive(data)) }, bytes)
    }

    /** Accept a resize. Ordered with input, because the host applies both in sequence. */
    fun resize(cols: Int, rows: Int): Boolean =
        admit(command("resize") { put("cols", JsonPrimitive(cols)); put("rows", JsonPrimitive(rows)) }, 0)

    /**
     * The commands that may go on the wire now, in sequence order. Each is counted as in flight
     * when it is returned, so the caller must send every one of them.
     */
    fun drain(): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        while (true) {
            val next = queued.firstOrNull() ?: break
            val limit = window?.maxBytes
            if (limit != null && inFlightBytes + next.frameBytes > limit) break
            queued.removeFirst()
            pending[next.sequence] = next
            inFlightBytes += next.frameBytes
            out += next.command
        }
        return out
    }

    /**
     * Release the command [ackSequence] the host acknowledged. Acks arrive strictly in order; one
     * that does not is a protocol violation, and false tells the caller to drop the attachment.
     */
    fun acknowledge(ackSequence: Long): Boolean {
        val entry = pending[ackSequence]
        if (ackSequence != acknowledged + 1 || entry == null) return false
        acknowledged = ackSequence
        pending.remove(ackSequence)
        inputBytes -= entry.inputBytes
        bufferedBytes -= entry.frameBytes
        inFlightBytes -= entry.frameBytes
        return true
    }

    private fun command(type: String, fill: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit): JsonObject {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "type" to JsonPrimitive(type),
            "sequence" to JsonPrimitive(sequence + 1),
        )
        fields.fill()
        return JsonObject(fields)
    }

    private fun admit(command: JsonObject, rawBytes: Int): Boolean {
        val frameBytes = command.toString().toByteArray(Charsets.UTF_8).size + (window?.frameOverheadBytes ?: 0)
        val windowLimit = window?.maxBytes
        if (windowLimit != null && frameBytes > windowLimit) return false
        if (maxBufferedBytes != null && bufferedBytes + frameBytes > maxBufferedBytes) return false
        sequence += 1
        queued.addLast(Entry(sequence, command, rawBytes, frameBytes))
        inputBytes += rawBytes
        bufferedBytes += frameBytes
        return true
    }
}
