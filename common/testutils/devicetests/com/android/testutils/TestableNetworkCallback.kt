/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.testutils

import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.util.Log
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatus
import com.android.testutils.RecorderCallback.CallbackEntry.BlockedStatusInt
import com.android.testutils.RecorderCallback.CallbackEntry.CapabilitiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.RecorderCallback.CallbackEntry.Losing
import com.android.testutils.RecorderCallback.CallbackEntry.Lost
import com.android.testutils.RecorderCallback.CallbackEntry.Resumed
import com.android.testutils.RecorderCallback.CallbackEntry.Suspended
import com.android.testutils.RecorderCallback.CallbackEntry.Unavailable
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

object NULL_NETWORK : Network(-1)
object ANY_NETWORK : Network(-2)
fun anyNetwork() = ANY_NETWORK

private val Int.capabilityName get() = NetworkCapabilities.capabilityNameOf(this)

open class RecorderCallback private constructor(
    private val backingRecord: ArrayTrackRecord<CallbackEntry>
) : NetworkCallback() {
    public constructor() : this(ArrayTrackRecord())
    protected constructor(src: RecorderCallback?): this(src?.backingRecord ?: ArrayTrackRecord())

    private val TAG = this::class.simpleName

    sealed class CallbackEntry {
        // To get equals(), hashcode(), componentN() etc for free, the child classes of
        // this class are data classes. But while data classes can inherit from other classes,
        // they may only have visible members in the constructors, so they couldn't declare
        // a constructor with a non-val arg to pass to CallbackEntry. Instead, force all
        // subclasses to implement a `network' property, which can be done in a data class
        // constructor by specifying override.
        abstract val network: Network

        data class Available(override val network: Network) : CallbackEntry()
        data class CapabilitiesChanged(
            override val network: Network,
            val caps: NetworkCapabilities
        ) : CallbackEntry()
        data class LinkPropertiesChanged(
            override val network: Network,
            val lp: LinkProperties
        ) : CallbackEntry()
        data class Suspended(override val network: Network) : CallbackEntry()
        data class Resumed(override val network: Network) : CallbackEntry()
        data class Losing(override val network: Network, val maxMsToLive: Int) : CallbackEntry()
        data class Lost(override val network: Network) : CallbackEntry()
        data class Unavailable private constructor(
            override val network: Network
        ) : CallbackEntry() {
            constructor() : this(NULL_NETWORK)
        }
        data class BlockedStatus(
            override val network: Network,
            val blocked: Boolean
        ) : CallbackEntry()
        data class BlockedStatusInt(
            override val network: Network,
            val blocked: Int
        ) : CallbackEntry()
        // Convenience constants for expecting a type
        companion object {
            @JvmField
            val AVAILABLE = Available::class
            @JvmField
            val NETWORK_CAPS_UPDATED = CapabilitiesChanged::class
            @JvmField
            val LINK_PROPERTIES_CHANGED = LinkPropertiesChanged::class
            @JvmField
            val SUSPENDED = Suspended::class
            @JvmField
            val RESUMED = Resumed::class
            @JvmField
            val LOSING = Losing::class
            @JvmField
            val LOST = Lost::class
            @JvmField
            val UNAVAILABLE = Unavailable::class
            @JvmField
            val BLOCKED_STATUS = BlockedStatus::class
            @JvmField
            val BLOCKED_STATUS_INT = BlockedStatusInt::class
        }
    }

    val history = backingRecord.newReadHead()
    val mark get() = history.mark

    override fun onAvailable(network: Network) {
        Log.d(TAG, "onAvailable $network")
        history.add(Available(network))
    }

    // PreCheck is not used in the tests today. For backward compatibility with existing tests that
    // expect the callbacks not to record this, do not listen to PreCheck here.

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
        Log.d(TAG, "onCapabilitiesChanged $network $caps")
        history.add(CapabilitiesChanged(network, caps))
    }

    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
        Log.d(TAG, "onLinkPropertiesChanged $network $lp")
        history.add(LinkPropertiesChanged(network, lp))
    }

    override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
        Log.d(TAG, "onBlockedStatusChanged $network $blocked")
        history.add(BlockedStatus(network, blocked))
    }

    // Cannot do:
    // fun onBlockedStatusChanged(network: Network, blocked: Int) {
    // because on S, that needs to be "override fun", and on R, that cannot be "override fun".
    override fun onNetworkSuspended(network: Network) {
        Log.d(TAG, "onNetworkSuspended $network $network")
        history.add(Suspended(network))
    }

    override fun onNetworkResumed(network: Network) {
        Log.d(TAG, "$network onNetworkResumed $network")
        history.add(Resumed(network))
    }

    override fun onLosing(network: Network, maxMsToLive: Int) {
        Log.d(TAG, "onLosing $network $maxMsToLive")
        history.add(Losing(network, maxMsToLive))
    }

    override fun onLost(network: Network) {
        Log.d(TAG, "onLost $network")
        history.add(Lost(network))
    }

    override fun onUnavailable() {
        Log.d(TAG, "onUnavailable")
        history.add(Unavailable())
    }
}

private const val DEFAULT_TIMEOUT = 200L // ms

open class TestableNetworkCallback private constructor(
    src: TestableNetworkCallback?,
    val defaultTimeoutMs: Long = DEFAULT_TIMEOUT
) : RecorderCallback(src) {
    @JvmOverloads
    constructor(timeoutMs: Long = DEFAULT_TIMEOUT): this(null, timeoutMs)

    fun createLinkedCopy() = TestableNetworkCallback(this, defaultTimeoutMs)

    // The last available network, or null if any network was lost since the last call to
    // onAvailable. TODO : fix this by fixing the tests that rely on this behavior
    val lastAvailableNetwork: Network?
        get() = when (val it = history.lastOrNull { it is Available || it is Lost }) {
            is Available -> it.network
            else -> null
        }

    fun pollForNextCallback(timeoutMs: Long = defaultTimeoutMs): CallbackEntry {
        return history.poll(timeoutMs) ?: fail("Did not receive callback after ${timeoutMs}ms")
    }

    // Make open for use in ConnectivityServiceTest which is the only one knowing its handlers.
    // TODO : remove the necessity to overload this, remove the open qualifier, and give a
    // default argument to assertNoCallback instead, possibly with @JvmOverloads if necessary.
    open fun assertNoCallback() = assertNoCallback(defaultTimeoutMs)

    fun assertNoCallback(timeoutMs: Long) {
        val cb = history.poll(timeoutMs)
        if (null != cb) fail("Expected no callback but got $cb")
    }

    fun assertNoCallbackThat(
        timeoutMs: Long = defaultTimeoutMs,
        valid: (CallbackEntry) -> Boolean
    ) {
        val cb = history.poll(timeoutMs) { valid(it) }.let {
            if (null != it) fail("Expected no callback but got $it")
        }
    }

    // Expects a callback of the specified type on the specified network within the timeout.
    // If no callback arrives, or a different callback arrives, fail. Returns the callback.
    inline fun <reified T : CallbackEntry> expectCallback(
        network: Network = ANY_NETWORK,
        timeoutMs: Long = defaultTimeoutMs
    ): T = pollForNextCallback(timeoutMs).let {
        if (it !is T || (ANY_NETWORK !== network && it.network != network)) {
            fail("Unexpected callback : $it, expected ${T::class} with Network[$network]")
        } else {
            it
        }
    }

    // Expects a callback of the specified type matching the predicate within the timeout.
    // Any callback that doesn't match the predicate will be skipped. Fails only if
    // no matching callback is received within the timeout.
    inline fun <reified T : CallbackEntry> eventuallyExpect(
        timeoutMs: Long = defaultTimeoutMs,
        from: Int = mark,
        crossinline predicate: (T) -> Boolean = { true }
    ): T = eventuallyExpectOrNull(timeoutMs, from, predicate).also {
        assertNotNull(it, "Callback ${T::class} not received within ${timeoutMs}ms")
    } as T

    fun <T : CallbackEntry> eventuallyExpect(
        type: KClass<T>,
        timeoutMs: Long = defaultTimeoutMs,
        predicate: (T: CallbackEntry) -> Boolean = { true }
    ) = history.poll(timeoutMs) { type.java.isInstance(it) && predicate(it) }.also {
        assertNotNull(it, "Callback ${type.java} not received within ${timeoutMs}ms")
    } as T

    // TODO (b/157405399) straighten and unify the method names
    inline fun <reified T : CallbackEntry> eventuallyExpectOrNull(
        timeoutMs: Long = defaultTimeoutMs,
        from: Int = mark,
        crossinline predicate: (T) -> Boolean = { true }
    ) = history.poll(timeoutMs, from) { it is T && predicate(it) } as T?

    fun expectCallbackThat(
        timeoutMs: Long = defaultTimeoutMs,
        valid: (CallbackEntry) -> Boolean
    ) = pollForNextCallback(timeoutMs).also { assertTrue(valid(it), "Unexpected callback : $it") }

    fun expectCapabilitiesThat(
        net: Network,
        tmt: Long = defaultTimeoutMs,
        valid: (NetworkCapabilities) -> Boolean
    ): CapabilitiesChanged {
        return expectCallback<CapabilitiesChanged>(net, tmt).also {
            assertTrue(valid(it.caps), "Capabilities don't match expectations ${it.caps}")
        }
    }

    fun expectLinkPropertiesThat(
        net: Network,
        tmt: Long = defaultTimeoutMs,
        valid: (LinkProperties) -> Boolean
    ): LinkPropertiesChanged {
        return expectCallback<LinkPropertiesChanged>(net, tmt).also {
            assertTrue(valid(it.lp), "LinkProperties don't match expectations ${it.lp}")
        }
    }

    // Expects onAvailable and the callbacks that follow it. These are:
    // - onSuspended, iff the network was suspended when the callbacks fire.
    // - onCapabilitiesChanged.
    // - onLinkPropertiesChanged.
    // - onBlockedStatusChanged.
    //
    // @param network the network to expect the callbacks on.
    // @param suspended whether to expect a SUSPENDED callback.
    // @param validated the expected value of the VALIDATED capability in the
    //        onCapabilitiesChanged callback.
    // @param tmt how long to wait for the callbacks.
    fun expectAvailableCallbacks(
        net: Network,
        suspended: Boolean = false,
        validated: Boolean? = true,
        blocked: Boolean = false,
        tmt: Long = defaultTimeoutMs
    ) {
        expectAvailableCallbacksCommon(net, suspended, validated, tmt)
        expectBlockedStatusCallback(blocked, net, tmt)
    }

    fun expectAvailableCallbacks(
        net: Network,
        suspended: Boolean,
        validated: Boolean,
        blockedStatus: Int,
        tmt: Long
    ) {
        expectAvailableCallbacksCommon(net, suspended, validated, tmt)
        expectBlockedStatusCallback(blockedStatus, net)
    }

    private fun expectAvailableCallbacksCommon(
        net: Network,
        suspended: Boolean,
        validated: Boolean?,
        tmt: Long
    ) {
        expectCallback<Available>(net, tmt)
        if (suspended) {
            expectCallback<Suspended>(net, tmt)
        }
        expectCapabilitiesThat(net, tmt) {
            validated == null || validated == it.hasCapability(
                NET_CAPABILITY_VALIDATED
            )
        }
        expectCallback<LinkPropertiesChanged>(net, tmt)
    }

    // Backward compatibility for existing Java code. Use named arguments instead and remove all
    // these when there is no user left.
    fun expectAvailableAndSuspendedCallbacks(
        net: Network,
        validated: Boolean,
        tmt: Long = defaultTimeoutMs
    ) = expectAvailableCallbacks(net, suspended = true, validated = validated, tmt = tmt)

    fun expectBlockedStatusCallback(blocked: Boolean, net: Network, tmt: Long = defaultTimeoutMs) {
        expectCallback<BlockedStatus>(net, tmt).also {
            assertEquals(blocked, it.blocked, "Unexpected blocked status ${it.blocked}")
        }
    }

    fun expectBlockedStatusCallback(blocked: Int, net: Network, tmt: Long = defaultTimeoutMs) {
        expectCallback<BlockedStatusInt>(net, tmt).also {
            assertEquals(blocked, it.blocked, "Unexpected blocked status ${it.blocked}")
        }
    }

    // Expects the available callbacks (where the onCapabilitiesChanged must contain the
    // VALIDATED capability), plus another onCapabilitiesChanged which is identical to the
    // one we just sent.
    // TODO: this is likely a bug. Fix it and remove this method.
    fun expectAvailableDoubleValidatedCallbacks(net: Network, tmt: Long = defaultTimeoutMs) {
        val mark = history.mark
        expectAvailableCallbacks(net, tmt = tmt)
        val firstCaps = history.poll(tmt, mark) { it is CapabilitiesChanged }
        assertEquals(firstCaps, expectCallback<CapabilitiesChanged>(net, tmt))
    }

    // Expects the available callbacks where the onCapabilitiesChanged must not have validated,
    // then expects another onCapabilitiesChanged that has the validated bit set. This is used
    // when a network connects and satisfies a callback, and then immediately validates.
    fun expectAvailableThenValidatedCallbacks(net: Network, tmt: Long = defaultTimeoutMs) {
        expectAvailableCallbacks(net, validated = false, tmt = tmt)
        expectCapabilitiesThat(net, tmt) { it.hasCapability(NET_CAPABILITY_VALIDATED) }
    }

    fun expectAvailableThenValidatedCallbacks(
        net: Network,
        blockedStatus: Int,
        tmt: Long = defaultTimeoutMs
    ) {
        expectAvailableCallbacks(net, validated = false, suspended = false,
                blockedStatus = blockedStatus, tmt = tmt)
        expectCapabilitiesThat(net, tmt) { it.hasCapability(NET_CAPABILITY_VALIDATED) }
    }

    // Temporary Java compat measure : have MockNetworkAgent implement this so that all existing
    // calls with networkAgent can be routed through here without moving MockNetworkAgent.
    // TODO: clean this up, remove this method.
    interface HasNetwork {
        val network: Network
    }

    @JvmOverloads
    open fun <T : CallbackEntry> expectCallback(
        type: KClass<T>,
        n: Network?,
        timeoutMs: Long = defaultTimeoutMs
    ) = pollForNextCallback(timeoutMs).also {
        val network = n ?: NULL_NETWORK
        // TODO : remove this .java access if the tests ever use kotlin-reflect. At the time of
        // this writing this would be the only use of this library in the tests.
        assertTrue(type.java.isInstance(it) && (ANY_NETWORK === n || it.network == network),
                "Unexpected callback : $it, expected ${type.java} with Network[$network]")
    } as T

    @JvmOverloads
    open fun <T : CallbackEntry> expectCallback(
        type: KClass<T>,
        n: HasNetwork?,
        timeoutMs: Long = defaultTimeoutMs
    ) = expectCallback(type, n?.network, timeoutMs)

    fun expectAvailableCallbacks(
        n: HasNetwork,
        suspended: Boolean,
        validated: Boolean,
        blocked: Boolean,
        timeoutMs: Long
    ) = expectAvailableCallbacks(n.network, suspended, validated, blocked, timeoutMs)

    fun expectAvailableAndSuspendedCallbacks(n: HasNetwork, expectValidated: Boolean) {
        expectAvailableAndSuspendedCallbacks(n.network, expectValidated)
    }

    fun expectAvailableCallbacksValidated(n: HasNetwork) {
        expectAvailableCallbacks(n.network)
    }

    fun expectAvailableCallbacksValidatedAndBlocked(n: HasNetwork) {
        expectAvailableCallbacks(n.network, blocked = true)
    }

    fun expectAvailableCallbacksUnvalidated(n: HasNetwork) {
        expectAvailableCallbacks(n.network, validated = false)
    }

    fun expectAvailableCallbacksUnvalidatedAndBlocked(n: HasNetwork) {
        expectAvailableCallbacks(n.network, validated = false, blocked = true)
    }

    fun expectAvailableDoubleValidatedCallbacks(n: HasNetwork) {
        expectAvailableDoubleValidatedCallbacks(n.network, defaultTimeoutMs)
    }

    fun expectAvailableThenValidatedCallbacks(n: HasNetwork) {
        expectAvailableThenValidatedCallbacks(n.network, defaultTimeoutMs)
    }

    @JvmOverloads
    fun expectLinkPropertiesThat(
        n: HasNetwork,
        tmt: Long = defaultTimeoutMs,
        valid: (LinkProperties) -> Boolean
    ) = expectLinkPropertiesThat(n.network, tmt, valid)

    @JvmOverloads
    fun expectCapabilitiesThat(
        n: HasNetwork,
        tmt: Long = defaultTimeoutMs,
        valid: (NetworkCapabilities) -> Boolean
    ) = expectCapabilitiesThat(n.network, tmt, valid)

    @JvmOverloads
    fun expectCapabilitiesWith(
        capability: Int,
        n: HasNetwork,
        timeoutMs: Long = defaultTimeoutMs
    ): NetworkCapabilities {
        return expectCapabilitiesThat(n.network, timeoutMs) { it.hasCapability(capability) }.caps
    }

    @JvmOverloads
    fun expectCapabilitiesWithout(
        capability: Int,
        n: HasNetwork,
        timeoutMs: Long = defaultTimeoutMs
    ): NetworkCapabilities {
        return expectCapabilitiesThat(n.network, timeoutMs) { !it.hasCapability(capability) }.caps
    }

    fun expectBlockedStatusCallback(expectBlocked: Boolean, n: HasNetwork) {
        expectBlockedStatusCallback(expectBlocked, n.network, defaultTimeoutMs)
    }
}
