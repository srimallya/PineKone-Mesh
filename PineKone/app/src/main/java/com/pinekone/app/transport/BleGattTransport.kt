package com.pinekone.app.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.pinekone.app.identity.IdentityRepository
import com.pinekone.app.protocol.PkFormats
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val BLE_TAG = "BleGattTransport"
private val UTF8: Charset = Charsets.UTF_8

private val SERVICE_UUID: UUID = UUID.fromString("3047f00d-72f9-4cce-9ada-ff45a149a4c1")
private val CTRL_CHAR_UUID: UUID = UUID.fromString("3047c7d0-5d41-4d07-9b73-2a6dc036e3a1")
private val DATA_CHAR_UUID: UUID = UUID.fromString("3047d4a0-f0d4-43c4-9b9b-30e5f0e0f0da")
private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class BleGattTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val identityRepository: IdentityRepository
) : Transport {

    override val kind: RadioKind = RadioKind.BLE

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val advertiser: BluetoothLeAdvertiser? get() = bluetoothAdapter?.bluetoothLeAdvertiser
    private val scanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private val peerEvents = MutableSharedFlow<PeerEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val peerFlows = ConcurrentHashMap<String, MutableSharedFlow<TransportFrame>>()
    private val deviceContexts = ConcurrentHashMap<String, BlePeerContext>()
    private val peerContexts = mutableMapOf<String, BlePeerContext>()
    private val peerMutex = Mutex()

    private val identityLoaded = AtomicBoolean(false)
    private lateinit var localHandshakeBytes: ByteArray
    private lateinit var localNodeId: String

    private var gattServer: BluetoothGattServer? = null
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d(BLE_TAG, "Server connection from ${device.address}")
                val context = deviceContexts.getOrPut(device.address) {
                    BlePeerContext(deviceAddress = device.address)
                }
                context.serverDevice = device
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d(BLE_TAG, "Server disconnected ${device.address}")
                handleDeviceDisconnected(device.address)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                if (!hasConnectPermission()) {
                    Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot respond to characteristic write from ${device.address}")
                } else {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    } catch (error: SecurityException) {
                        Log.e(BLE_TAG, "SecurityException while responding to characteristic write from ${device.address}", error)
                    }
                }
            }
            handleIncomingBytes(device.address, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                if (!hasConnectPermission()) {
                    Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot respond to descriptor write from ${device.address}")
                } else {
                    try {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    } catch (error: SecurityException) {
                        Log.e(BLE_TAG, "SecurityException while responding to descriptor write from ${device.address}", error)
                    }
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(BLE_TAG, "Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(BLE_TAG, "Advertising failed: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address
            if (deviceContexts.containsKey(address)) return
            if (!result.scanRecord?.serviceUuids.orEmpty().any { it.uuid == SERVICE_UUID }) return
            Log.d(BLE_TAG, "Scan found ${device.address}")
            val context = deviceContexts.getOrPut(address) { BlePeerContext(deviceAddress = address) }
            connectAsClient(device, context)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(BLE_TAG, "Scan failed: $errorCode")
        }
    }

    override fun startDiscovery() {
        if (!ensureReady()) return
        val scanner = scanner ?: return
        if (!hasScanPermission()) {
            Log.w(BLE_TAG, "Missing BLE scan permission; cannot start discovery")
            return
        }
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (error: SecurityException) {
            Log.e(BLE_TAG, "SecurityException while starting BLE discovery", error)
        }
    }

    override fun stopDiscovery() {
        val scanner = scanner ?: return
        if (!hasScanPermission()) {
            Log.w(BLE_TAG, "Missing BLE scan permission; cannot stop discovery")
            return
        }
        try {
            scanner.stopScan(scanCallback)
        } catch (error: SecurityException) {
            Log.e(BLE_TAG, "SecurityException while stopping BLE discovery", error)
        }
    }

    override fun advertise(on: Boolean) {
        if (!ensureReady()) return
        val advertiser = advertiser ?: return
        if (on) {
            if (gattServer == null) {
                if (!hasConnectPermission()) {
                    Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot open GATT server")
                    return
                }
                try {
                    gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)?.apply {
                        addService(buildPrimaryService())
                    }
                } catch (error: SecurityException) {
                    Log.e(BLE_TAG, "SecurityException while opening GATT server", error)
                    return
                }
            }
            if (gattServer == null) {
                Log.w(BLE_TAG, "GATT server unavailable; skipping advertisement start")
                return
            }
            if (!hasAdvertisePermission()) {
                Log.w(BLE_TAG, "Missing BLUETOOTH_ADVERTISE permission; cannot start advertising")
                return
            }
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .addServiceData(ParcelUuid(SERVICE_UUID), shortBeaconPayload())
                .build()
            try {
                advertiser.startAdvertising(settings, data, advertiseCallback)
            } catch (error: SecurityException) {
                Log.e(BLE_TAG, "SecurityException while starting BLE advertising", error)
            }
        } else {
            if (hasAdvertisePermission()) {
                try {
                    advertiser.stopAdvertising(advertiseCallback)
                } catch (error: SecurityException) {
                    Log.e(BLE_TAG, "SecurityException while stopping BLE advertising", error)
                }
            } else {
                Log.w(BLE_TAG, "Missing BLUETOOTH_ADVERTISE permission; skipping stopAdvertising call")
            }
            if (hasConnectPermission()) {
                try {
                    gattServer?.close()
                } catch (error: SecurityException) {
                    Log.e(BLE_TAG, "SecurityException while closing GATT server", error)
                }
            } else {
                Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; skipping GATT server close")
            }
            gattServer = null
        }
    }

    override fun peers(): Flow<PeerEvent> = peerEvents.asSharedFlow()

    override fun open(peer: RadioPeer): Flow<TransportFrame> {
        val stream = peerFlows.getOrPut(peer.id) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 32,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
        return stream.asSharedFlow()
    }

    override suspend fun send(peer: RadioPeer, frame: ByteArray): Boolean {
        ensureReady()
        val context = peerMutex.withLock { peerContexts[peer.id] }
        val dataChar = outgoingDataCharacteristic
        if (context == null || dataChar == null) {
            Log.w(BLE_TAG, "No BLE context for peer ${peer.id}")
            return false
        }
        val gatt = context.gatt
        val serverDevice = context.serverDevice
        return when {
            gatt != null -> {
                val service = gatt.getService(SERVICE_UUID) ?: return false
                val characteristic = service.getCharacteristic(DATA_CHAR_UUID) ?: return false
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                if (!hasConnectPermission()) {
                    Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot write data to ${peer.id}")
                    return false
                }
                try {
                    characteristic.value = frame
                    gatt.writeCharacteristic(characteristic)
                } catch (error: SecurityException) {
                    Log.e(BLE_TAG, "SecurityException while writing BLE data to ${peer.id}", error)
                    false
                }
            }
            serverDevice != null -> {
                if (!hasConnectPermission()) {
                    Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot notify ${peer.id}")
                    return false
                }
                try {
                    dataChar.value = frame
                    gattServer?.notifyCharacteristicChanged(serverDevice, dataChar, false) ?: false
                } catch (error: SecurityException) {
                    Log.e(BLE_TAG, "SecurityException while notifying BLE data to ${peer.id}", error)
                    false
                }
            }
            else -> false
        }
    }

    override fun isAvailable(): Boolean =
        bluetoothAdapter?.isEnabled == true &&
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)

    @SuppressLint("MissingPermission")
    private fun ensureReady(): Boolean {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.w(BLE_TAG, "Bluetooth adapter is disabled")
            return false
        }
        if (!identityLoaded.get()) {
            runBlocking {
                runCatching {
                    val identity = identityRepository.getIdentity()
                    localNodeId = identity.nodeId
                    val packet = MeshPacket.Handshake(
                        nodeId = identity.nodeId,
                        displayName = identity.displayName,
                        publicKey = identity.publicKey,
                        fingerprint = identity.fingerprint
                    )
                    localHandshakeBytes =
                        PkFormats.json.encodeToString(MeshPacket.serializer(), packet).toByteArray(UTF8)
                    identityLoaded.set(true)
                }.onFailure { t ->
                    Log.e(BLE_TAG, "Failed to load identity", t)
                }
            }
        }
        return true
    }

    private fun shortBeaconPayload(): ByteArray {
        return byteArrayOf(
            0x01,
            0x30,
            0x47,
            0x00,
            0x00,
            0x00
        )
    }

    private fun buildPrimaryService(): BluetoothGattService {
        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val ctrlChar = BluetoothGattCharacteristic(
            CTRL_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
        )
        val ctrlDesc = BluetoothGattDescriptor(
            CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        ctrlChar.addDescriptor(ctrlDesc)

        val dataChar = BluetoothGattCharacteristic(
            DATA_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val dataDesc = BluetoothGattDescriptor(
            CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        dataChar.addDescriptor(dataDesc)

        service.addCharacteristic(ctrlChar)
        service.addCharacteristic(dataChar)
        return service
    }

    private val outgoingDataCharacteristic: BluetoothGattCharacteristic?
        get() = gattServer
            ?.getService(SERVICE_UUID)
            ?.getCharacteristic(DATA_CHAR_UUID)

    private fun connectAsClient(device: BluetoothDevice, peerContext: BlePeerContext) {
        if (!hasConnectPermission()) {
            Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot open GATT client to ${device.address}")
            return
        }
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    Log.d(BLE_TAG, "Client connected to ${device.address}")
                    peerContext.gatt = gatt
                    if (hasConnectPermission()) {
                        try {
                            gatt.discoverServices()
                        } catch (error: SecurityException) {
                            Log.e(BLE_TAG, "SecurityException while discovering services for ${device.address}", error)
                        }
                    } else {
                        Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot discover services for ${device.address}")
                    }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.d(BLE_TAG, "Client disconnected ${device.address}")
                    peerContext.gatt = null
                    if (hasConnectPermission()) {
                        try {
                            gatt.close()
                        } catch (error: SecurityException) {
                            Log.e(BLE_TAG, "SecurityException while closing GATT for ${device.address}", error)
                        }
                    } else {
                        Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot close GATT for ${device.address}")
                    }
                    handleDeviceDisconnected(device.address)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                if (!hasConnectPermission()) {
                    Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; skipping service config for ${device.address}")
                    return
                }
                val ctrl = gatt.getService(SERVICE_UUID)?.getCharacteristic(CTRL_CHAR_UUID)
                val data = gatt.getService(SERVICE_UUID)?.getCharacteristic(DATA_CHAR_UUID)
                if (ctrl != null) {
                    try {
                        gatt.setCharacteristicNotification(ctrl, true)
                    } catch (error: SecurityException) {
                        Log.e(BLE_TAG, "SecurityException enabling control notifications for ${device.address}", error)
                    }
                    try {
                        ctrl.value = localHandshakeBytes
                        gatt.writeCharacteristic(ctrl)
                    } catch (error: SecurityException) {
                        Log.e(BLE_TAG, "SecurityException writing handshake to ${device.address}", error)
                    }
                }
                if (data != null) {
                    try {
                        gatt.setCharacteristicNotification(data, true)
                    } catch (error: SecurityException) {
                        Log.e(BLE_TAG, "SecurityException enabling data notifications for ${device.address}", error)
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                handleIncomingBytes(device.address, characteristic.value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(BLE_TAG, "Characteristic write failed for ${device.address}: $status")
                }
            }
        }
        try {
            device.connectGatt(this@BleGattTransport.context, false, callback)
        } catch (error: SecurityException) {
            Log.e(BLE_TAG, "SecurityException while connecting GATT client ${device.address}", error)
        }
    }

    private fun handleIncomingBytes(address: String, bytes: ByteArray) {
        scope.launch {
            val packet = runCatching {
                val json = bytes.toString(UTF8)
                PkFormats.json.decodeFromString(MeshPacket.serializer(), json)
            }.getOrElse {
                Log.e(BLE_TAG, "Failed to decode incoming BLE payload", it)
                return@launch
            }

            val context = deviceContexts.getOrPut(address) { BlePeerContext(deviceAddress = address) }
            when (packet) {
                is MeshPacket.Handshake -> handleHandshake(context, packet)
                is MeshPacket.EnvelopePacket -> emitFrame(context, packet.toBytes(bytes))
                is MeshPacket.ControlPacket -> emitFrame(context, packet.toBytes(bytes))
            }
        }
    }

    private suspend fun handleHandshake(context: BlePeerContext, packet: MeshPacket.Handshake) {
        val radioPeer = RadioPeer(
            id = packet.nodeId,
            displayName = packet.displayName,
            fingerprint = packet.fingerprint,
            publicKey = packet.publicKey,
            kind = kind,
            metadata = mapOf(
                "maxFanout" to packet.capabilities.maxFanout,
                "minBatteryPct" to packet.capabilities.minBatteryPct,
                "quality" to 0.6
            )
        )
        context.radioPeer = radioPeer
        peerFlows.getOrPut(radioPeer.id) {
            MutableSharedFlow(
                replay = 0,
                extraBufferCapacity = 32,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
        peerMutex.withLock {
            peerContexts[radioPeer.id] = context
        }
        peerEvents.emit(PeerEvent.Upsert(radioPeer))

        val gatt = context.gatt
        val device = context.serverDevice
        when {
            gatt != null -> {
                val ctrl = gatt.getService(SERVICE_UUID)?.getCharacteristic(CTRL_CHAR_UUID)
                if (ctrl != null) {
                    if (!hasConnectPermission()) {
                        Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot write handshake to ${radioPeer.id}")
                    } else {
                        try {
                            ctrl.value = localHandshakeBytes
                            gatt.writeCharacteristic(ctrl)
                        } catch (error: SecurityException) {
                            Log.e(BLE_TAG, "SecurityException writing handshake to ${radioPeer.id}", error)
                        }
                    }
                }
            }
            device != null -> {
                val ctrl = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(CTRL_CHAR_UUID)
                if (ctrl != null) {
                    if (!hasConnectPermission()) {
                        Log.w(BLE_TAG, "Missing BLUETOOTH_CONNECT permission; cannot notify handshake to ${radioPeer.id}")
                    } else {
                        try {
                            ctrl.value = localHandshakeBytes
                            gattServer?.notifyCharacteristicChanged(device, ctrl, false)
                        } catch (error: SecurityException) {
                            Log.e(BLE_TAG, "SecurityException notifying handshake to ${radioPeer.id}", error)
                        }
                    }
                }
            }
        }
    }

    private suspend fun emitFrame(context: BlePeerContext, bytes: ByteArray) {
        val peer = context.radioPeer ?: return
        peerFlows[peer.id]?.emit(
            TransportFrame(
                bytes = bytes,
                type = TransportFrame.FrameType.PAYLOAD
            )
        )
    }

    private fun MeshPacket.EnvelopePacket.toBytes(original: ByteArray): ByteArray = original
    private fun MeshPacket.ControlPacket.toBytes(original: ByteArray): ByteArray = original

    private fun handleDeviceDisconnected(address: String) {
        scope.launch {
            val context = deviceContexts.remove(address) ?: return@launch
            val radioPeer = context.radioPeer ?: return@launch
            peerMutex.withLock { peerContexts.remove(radioPeer.id) }
            peerFlows.remove(radioPeer.id)
            peerEvents.emit(PeerEvent.Removed(radioPeer.id, kind))
        }
    }

    private data class BlePeerContext(
        val deviceAddress: String,
        val inbound: MutableSharedFlow<TransportFrame> = MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        ),
        var gatt: BluetoothGatt? = null,
        var serverDevice: BluetoothDevice? = null,
        var radioPeer: RadioPeer? = null
    )

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

}
