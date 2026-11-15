package com.letsgo.chageun.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.listener.AmbientLightBrightnessListener
import android.util.Log
import com.letsgo.chageun.core.EffectControl
import com.letsgo.chageun.core.EffectKey
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Atmosphere effects through Vehicle control APIs (console: "준비 중").
 * Everything here is optional: when control is not granted, [canControl] is false and the app runs on screen and sound.
 */
class PleosEffects(
    private val vehicle: Vehicle,
    private val effectsEnabled: () -> Boolean,
    /** Every brightness the car reports, so the ledger can tell when a person changed it after the app did. */
    private val onObserved: (EffectKey, Int) -> Unit,
) : EffectControl {
    private var ambientArea: SeatArea? = null
    private var ambientWritable = false
    private val ambientListener = object : AmbientLightBrightnessListener {
        override fun onBrightnessUpdated(value: Int?) {
            Log.i(TAG, "ambient reported $value")
            value?.let { onObserved(EffectKey.AMBIENT_BRIGHTNESS, it) }
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "ambient listener", e) }
    }

    /** Call once the vehicle service is bound. */
    fun probe() {
        runCatching {
            vehicle.getLight().checkAmbientLightBrightnessCapabilities({ config, areas ->
                val area = areas?.keys?.firstOrNull()
                ambientArea = area
                ambientWritable = config?.accessType == AccessType.READ_WRITE || config?.accessType == AccessType.WRITE
                if (area != null) runCatching { vehicle.getLight().registerAmbientLightBrightnessListener(area, ambientListener) }
                    .onFailure { Log.w(TAG, "ambient listener register", it) }
            }, { e -> Log.w(TAG, "ambient capability", e) })
        }.onFailure { Log.w(TAG, "ambient capability threw", it) }
    }

    fun release() {
        ambientArea?.let { area -> runCatching { vehicle.getLight().unregisterAmbientLightBrightnessListener(area, ambientListener) } }
    }

    /** Permission only: turning effects off in settings must not stop the app from putting back what it changed (L3). */
    override fun canControl(key: EffectKey): Boolean =
        key == EffectKey.AMBIENT_BRIGHTNESS && ambientWritable && ambientArea != null

    override fun mayApply(key: EffectKey): Boolean = effectsEnabled() && canControl(key)

    override suspend fun read(key: EffectKey): Int? {
        val area = ambientArea ?: return null
        return call<Int> { done ->
            vehicle.getLight().getAmbientLightBrightness(area, { done(it) }, { e -> Log.w(TAG, "ambient read", e); done(null) })
        }.also { Log.i(TAG, "ambient read -> $it") }
    }

    override suspend fun write(key: EffectKey, value: Int): Boolean {
        val area = ambientArea ?: return false
        return call<Boolean> { done ->
            vehicle.getLight().setAmbientLightBrightness(area, value, { _, _ -> done(true) }, { e -> Log.w(TAG, "ambient write", e); done(false) })
        }.let { ok -> (ok ?: false).also { Log.i(TAG, "ambient write $value -> $it") } }
    }

    private suspend fun <T> call(block: (done: (T?) -> Unit) -> Unit): T? = withTimeoutOrNull(TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            runCatching { block { if (cont.isActive) cont.resume(it) } }
                .onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    companion object {
        private const val TAG = "Chageun"
        private const val TIMEOUT_MS = 3_000L
    }
}
