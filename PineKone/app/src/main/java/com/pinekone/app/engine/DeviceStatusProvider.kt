package com.pinekone.app.engine

import android.content.Context
import android.os.BatteryManager

interface DeviceStatusProvider {
    fun currentStatus(): DeviceStatus
}

class SystemDeviceStatusProvider(
    private val context: Context
) : DeviceStatusProvider {
    override fun currentStatus(): DeviceStatus {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
        return DeviceStatus(
            batteryPct = level,
            queueSlack = 1.0
        )
    }
}
