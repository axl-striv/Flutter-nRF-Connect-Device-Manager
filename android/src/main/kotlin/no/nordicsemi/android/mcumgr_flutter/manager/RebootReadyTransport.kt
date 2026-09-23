package no.nordicsemi.android.mcumgr_flutter.manager

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.runtime.mcumgr.McuMgrCallback
import io.runtime.mcumgr.McuMgrHeader
import io.runtime.mcumgr.McuMgrTransport
import io.runtime.mcumgr.ble.exception.McuMgrDisconnectedException
import io.runtime.mcumgr.exception.McuMgrErrorException
import io.runtime.mcumgr.exception.McuMgrException
import io.runtime.mcumgr.exception.McuMgrTimeoutException
import io.runtime.mcumgr.managers.ImageManager
import io.runtime.mcumgr.util.CBOR
import io.runtime.mcumgr.response.McuMgrResponse
import io.runtime.mcumgr.response.img.McuMgrImageStateResponse

internal interface RebootScheduler {
    fun now(): Long
    fun post(delay: Long, action: () -> Unit): () -> Unit
}

private class MainRebootScheduler : RebootScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun now() = SystemClock.elapsedRealtime()
    override fun post(delay: Long, action: () -> Unit): () -> Unit {
        val work = Runnable(action)
        handler.postDelayed(work, delay)
        return { handler.removeCallbacks(work) }
    }
}

/**
 * TEST_AND_CONFIRM only: replace Nordic's fixed swap sleep with a bounded live
 * image-list probe before its post-reset confirm. Nordic still uploads, resets,
 * confirms and validates the confirmation response itself. Reads use the raw
 * transport so the gate never recursively intercepts its own requests.
 */
internal class RebootReadyTransport(
    private val transport: McuMgrTransport,
    private val scheduler: RebootScheduler = MainRebootScheduler(),
    private val log: (String) -> Unit = {},
) : McuMgrTransport by transport {
    private var budget = 0L
    private var targets: Map<Int, List<ByteArray>> = emptyMap()
    private var resetAt: Long? = null
    private var generation = 0
    private var cancelDeadline: (() -> Unit)? = null
    var isWaiting = false
        private set

    fun configure(timeout: Long, hashes: Map<Int, List<ByteArray>>) {
        cancelPending()
        budget = timeout
        targets = hashes
    }

    fun cancelPending() {
        generation++
        cancelDeadline?.invoke()
        cancelDeadline = null
        resetAt = null
        isWaiting = false
    }

    override fun release() {
        cancelPending()
        transport.release()
    }

    override fun <T : McuMgrResponse> send(
        payload: ByteArray, timeout: Long, responseType: Class<T>, callback: McuMgrCallback<T>,
    ) {
        val header = McuMgrHeader.fromBytes(payload)
        if (budget <= 0 || targets.isEmpty() || header.op != 2) {
            transport.send(payload, timeout, responseType, callback)
            return
        }
        // SMP OS reset (group 0, command 5). If the disconnect beats its ACK,
        // use send time; a successful ACK refines the start of the budget.
        if (header.groupId == 0 && header.commandId == 5) {
            resetAt = scheduler.now()
            val current = generation
            transport.send(payload, timeout, responseType, object : McuMgrCallback<T> {
                override fun onResponse(response: T) {
                    if (current != generation) return
                    if (response.isSuccess && !isWaiting) resetAt = scheduler.now()
                    callback.onResponse(response)
                }
                override fun onError(error: McuMgrException) {
                    if (current == generation) callback.onError(error)
                }
            })
            return
        }
        val started = resetAt
        // The state write after reset is Nordic's confirm, not a new upload.
        if (started == null || header.groupId != 1 || header.commandId != 0) {
            transport.send(payload, timeout, responseType, callback)
            return
        }
        // Test and confirm share the same command. An initial bootloader reset
        // may precede upload, so never hold a test (confirm=false) write.
        val confirming = try {
            CBOR.toObjectMap(payload.copyOfRange(McuMgrHeader.HEADER_LENGTH, payload.size))["confirm"] == true
        } catch (error: Exception) {
            callback.onError(McuMgrException(error))
            return
        }
        if (!confirming) {
            transport.send(payload, timeout, responseType, callback)
            return
        }
        resetAt = null
        isWaiting = true
        val current = generation
        val deadline = started + budget
        fun active() = current == generation && isWaiting
        fun finish(error: McuMgrException?) {
            if (!active()) return
            isWaiting = false
            cancelDeadline?.invoke()
            cancelDeadline = null
            log("Reboot readiness ${if (error == null) "ready" else "failed"} after ${scheduler.now() - started} ms")
            if (error != null) {
                transport.release()
                callback.onError(error)
            } else {
                // Preserve Nordic's confirm; being active alone is not success.
                transport.send(payload, timeout, responseType, callback)
            }
        }
        fun expired(): Boolean {
            if (scheduler.now() < deadline) return false
            finish(McuMgrTimeoutException())
            return true
        }
        fun probe() {
            if (!active() || expired()) return
            ImageManager(transport).list(object : McuMgrCallback<McuMgrImageStateResponse> {
                override fun onResponse(response: McuMgrImageStateResponse) {
                    scheduler.post(0) {
                        if (!active() || expired()) return@post
                        when {
                            !response.isSuccess -> finish(McuMgrErrorException(response))
                            response.images.isNullOrEmpty() -> finish(McuMgrException("Missing reboot image list"))
                            targets.all { (index, hashes) -> response.images.any { slot ->
                                slot.image == index && slot.active && hashes.any { it.contentEquals(slot.hash) }
                            } } -> finish(null)
                            else -> scheduler.post(2000) { probe() }
                        }
                    }
                }
                override fun onError(error: McuMgrException) {
                    scheduler.post(0) {
                        if (!active() || expired()) return@post
                        if (error is McuMgrTimeoutException || error is McuMgrDisconnectedException) {
                            scheduler.post(2000) { probe() }
                        } else finish(error) // Permission, pairing and protocol errors are terminal.
                    }
                }
            })
        }
        log("Waiting for active target image; reboot budget=$budget ms")
        cancelDeadline = scheduler.post((deadline - scheduler.now()).coerceAtLeast(0)) {
            finish(McuMgrTimeoutException())
        }
        scheduler.post((started + 1000 - scheduler.now()).coerceAtLeast(0)) { probe() }
    }
}
