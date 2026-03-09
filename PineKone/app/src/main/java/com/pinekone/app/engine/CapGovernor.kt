package com.pinekone.app.engine

import com.pinekone.app.protocol.PkPolicy
import kotlin.math.max

data class DeviceCaps(
    val maxFanoutDevice: Int,
    val transmitBudget: Double,
    val minBatteryPct: Int
)

data class DeviceStatus(
    val batteryPct: Int,
    val queueSlack: Double
)

sealed interface ForwardDecision {
    data class Allowed(val fanout: Int) : ForwardDecision
    data object StoreCarry : ForwardDecision
    data object DeclinedBattery : ForwardDecision
}

class CapGovernor(
    private val caps: DeviceCaps
) {
    fun batteryFloor(policy: PkPolicy): Int = max(caps.minBatteryPct, policy.minBattPct)

    fun evaluate(policy: PkPolicy, status: DeviceStatus, storeCarryRequested: Boolean): ForwardDecision {
        val batteryFloor = batteryFloor(policy)
        if (status.batteryPct < batteryFloor) {
            return if (storeCarryRequested) ForwardDecision.StoreCarry else ForwardDecision.DeclinedBattery
        }

        val policyFanout = policy.maxFanout
        if (policyFanout <= 0) return ForwardDecision.StoreCarry

        val budgetRatio = caps.transmitBudget.coerceIn(0.0, 1.0)
        val slackRatio = status.queueSlack.coerceIn(0.0, 1.0)

        val budgetFanout = if (budgetRatio <= 0.0) 0 else max(1, (policyFanout * budgetRatio).toInt())
        val slackFanout = if (slackRatio <= 0.0) 0 else max(1, (policyFanout * slackRatio).toInt())
        val allowedFanout = listOf(
            policyFanout,
            caps.maxFanoutDevice,
            budgetFanout,
            slackFanout
        ).filter { it > 0 }.minOrNull() ?: 0

        if (allowedFanout <= 0) {
            return if (storeCarryRequested) ForwardDecision.StoreCarry else ForwardDecision.DeclinedBattery
        }
        return ForwardDecision.Allowed(allowedFanout)
    }
}
