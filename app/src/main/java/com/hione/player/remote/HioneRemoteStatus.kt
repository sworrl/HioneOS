package com.hione.player.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live, observable state of [HioneRemoteGattService] for the Settings card. Every field is written
 * by the service from real callbacks (advertise success/failure, connection state changes, pair
 * results) — nothing here is inferred or optimistic, so the UI never shows a connection that
 * doesn't exist.
 */
object HioneRemoteStatus {

    data class Phone(
        /** BLE address for the life of the connection (phones rotate random addresses). */
        val address: String,
        /** Bonded-device name if the stack knows one, else null. */
        val btName: String?,
        /** Label the PWA sent when it paired / authenticated (e.g. "Chrome on Android"). */
        val label: String?,
        val authorized: Boolean,
        val mtu: Int
    ) {
        val displayName: String get() = label ?: btName ?: address
    }

    data class State(
        /** Service process is up and holding a GATT server. */
        val running: Boolean = false,
        /** BLE advertising confirmed started by the stack (onStartSuccess). */
        val advertising: Boolean = false,
        /** Current one-time 6-digit pairing code ("" until the service generates one). */
        val pairingCode: String = "",
        /** Pairing temporarily refused after too many wrong codes; epoch ms when it lifts. */
        val pairLockedUntilMs: Long = 0L,
        val connected: List<Phone> = emptyList(),
        /** Name the adapter is currently advertising under. */
        val advertisedName: String = "",
        /** Last failure worth surfacing (permission missing, BT off, advertise error…). */
        val lastError: String? = null,
        /** Bump so the paired-list UI re-reads prefs after a pair/forget. */
        val pairedGeneration: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    internal fun update(block: (State) -> State) {
        _state.value = block(_state.value)
    }

    internal fun reset() {
        _state.value = State(pairedGeneration = _state.value.pairedGeneration)
    }
}
