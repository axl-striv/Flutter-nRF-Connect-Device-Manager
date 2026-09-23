package no.nordicsemi.android.mcumgr_flutter.manager

import io.runtime.mcumgr.*
import io.runtime.mcumgr.exception.*
import io.runtime.mcumgr.managers.ImageManager
import io.runtime.mcumgr.response.McuMgrResponse
import io.runtime.mcumgr.response.dflt.McuMgrOsResponse
import io.runtime.mcumgr.response.img.McuMgrImageStateResponse
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class RebootReadyTransportTest {
    private class Clock : RebootScheduler {
        var time = 0L
        val work = mutableMapOf<() -> Unit, Long>()
        override fun now() = time
        override fun post(delay: Long, action: () -> Unit): () -> Unit {
            work[action] = time + delay
            return { work.remove(action); Unit }
        }
        fun advance(end: Long) {
            while (true) {
                val next = work.minByOrNull { it.value } ?: break
                if (next.value > end) break
                work.remove(next.key); time = next.value; next.key()
            }
            time = end
        }
    }
    private class Rig {
        val clock = Clock()
        var confirms = 0
        var errors = 0
        var probe: (McuMgrCallback<McuMgrImageStateResponse>) -> Unit = {}
        @Suppress("UNCHECKED_CAST")
        val raw = Proxy.newProxyInstance(McuMgrTransport::class.java.classLoader,
            arrayOf(McuMgrTransport::class.java)) { _, method, args ->
            when (method.name) {
                "getScheme" -> McuMgrScheme.BLE
                "send" -> {
                    val h = McuMgrHeader.fromBytes(args!![0] as ByteArray)
                    val cb = args[3] as McuMgrCallback<McuMgrResponse>
                    when {
                        h.groupId == 0 -> cb.onResponse(McuMgrOsResponse())
                        h.op == 0 -> probe(cb as McuMgrCallback<McuMgrImageStateResponse>)
                        else -> confirms++
                    }; null
                }
                else -> null
            }
        } as McuMgrTransport
        val gate = RebootReadyTransport(raw, clock)
        fun start(budget: Long = 75000) {
            gate.configure(budget, mapOf(0 to listOf(byteArrayOf(7))))
            val cb = object : McuMgrCallback<McuMgrResponse> {
                override fun onResponse(response: McuMgrResponse) {}
                override fun onError(error: McuMgrException) { errors++ }
            }
            gate.send(McuMgrHeader(0, 2, 0, 0, 0, 0, 5).toBytes(),
                10000, McuMgrResponse::class.java, cb)
            // A test write after an initial reset must pass through unblocked.
            val imageCallback = object : McuMgrCallback<McuMgrImageStateResponse> {
                override fun onResponse(response: McuMgrImageStateResponse) {}
                override fun onError(error: McuMgrException) { errors++ }
            }
            ImageManager(gate).test(byteArrayOf(7), imageCallback)
            assertEquals(1, confirms); confirms = 0
            ImageManager(gate).confirm(byteArrayOf(7), imageCallback)
        }
        fun image(target: Boolean) = McuMgrImageStateResponse().apply {
            images = arrayOf(McuMgrImageStateResponse.ImageSlot().apply {
                image = 0; active = true; hash = byteArrayOf(if (target) 7 else 8)
            })
        }
    }
    @Test fun readyEndsEarlyAndOldImageKeepsWaiting() {
        for (readyAt in listOf(25000L, 50000L, 74000L)) {
            val r = Rig()
            r.probe = { cb -> r.clock.post(minOf(1000, (readyAt - r.clock.time).coerceAtLeast(0))) {
                cb.onResponse(r.image(r.clock.time >= readyAt))
            }; Unit }
            r.start(); r.clock.advance(readyAt - 1); assertEquals(0, r.confirms)
            r.clock.advance(74999); assertEquals(1, r.confirms); assertEquals(0, r.errors)
        }
    }
    @Test fun deadlineCoversHungReadAndIgnoresLateSuccess() {
        for (transientFailure in listOf(false, true)) {
            val r = Rig(); var pending: McuMgrCallback<McuMgrImageStateResponse>? = null
            r.probe = { pending = it; if (transientFailure) it.onError(McuMgrTimeoutException()) }
            r.start(); r.clock.advance(74999); assertEquals(0, r.errors)
            r.clock.advance(75000); pending!!.onResponse(r.image(true)); r.clock.advance(80000)
            assertEquals(1, r.errors); assertEquals(0, r.confirms)
        }
    }
    @Test fun cancellationInvalidatesOldCallbacksAndDeadline() {
        val r = Rig(); var pending: McuMgrCallback<McuMgrImageStateResponse>? = null
        r.probe = { pending = it }; r.start(); r.clock.advance(1000); r.gate.cancelPending()
        pending!!.onResponse(r.image(true)); r.clock.advance(80000)
        assertEquals(0, r.confirms); assertEquals(0, r.errors)
        r.probe = { it.onResponse(r.image(true)) }; r.start(); r.clock.advance(82000)
        assertEquals(1, r.confirms)
    }
    @Test fun malformedAndPermanentErrorsFailAndDisabledGatePassesThrough() {
        for (malformed in listOf(false, true)) {
            val r = Rig(); r.probe = { if (malformed) it.onResponse(McuMgrImageStateResponse())
                else it.onError(McuMgrException("Pairing removed")) }
            r.start(); r.clock.advance(1000); assertEquals(1, r.errors); assertEquals(0, r.confirms)
        }
        val r = Rig(); r.start(0); assertEquals(1, r.confirms)
    }
}
