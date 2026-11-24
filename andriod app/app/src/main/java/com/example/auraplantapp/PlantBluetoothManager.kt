
package com.example.auraplantapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID

class PlantBluetoothManager(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit,
    private val onConnectionEvent: (Boolean) -> Unit = {}
) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val adapter = bluetoothManager.adapter
    private val scanner = adapter?.bluetoothLeScanner

    private var hasBeenReady = false
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var isScanning = false

    companion object {
        private const val TAG = "AuraPlantBLE"

        val SERVICE_UUID: UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        val RX_UUID: UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        val TX_UUID: UUID =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

        private val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // STEP 1: Scan for the TinyCircuits board
    @SuppressLint("MissingPermission")
    fun scanForPlant() {

        if (bluetoothGatt != null) {
            onStatusChanged("Already connected or connecting")
            return
        }

        if (isScanning) {
            onStatusChanged("Already scanning...")
            return
        }

        val bleScanner = scanner

        if (bleScanner == null) {
            onStatusChanged("Bluetooth scanner unavailable")
            return
        }

        try {
            val filter = ScanFilter.Builder()
                .setDeviceName("BlueNRG")
                .build()

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            isScanning = true
            onStatusChanged("Searching for TinyCircuits...")

            bleScanner.startScan(
                listOf(filter),
                settings,
                scanCallback
            )

        } catch (e: Exception) {
            isScanning = false
            Log.e(TAG, "Scan error", e)
            onStatusChanged("Scan error: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val device = result.device
            val deviceName =
                result.scanRecord?.deviceName ?: device.name

            Log.d(TAG, "Found BLE device: $deviceName")

            if (deviceName != "BlueNRG") {
                return
            }

            scanner?.stopScan(this)
            isScanning = false

            onStatusChanged("Found TinyCircuits! Connecting...")

            connectToPlant(device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "Scan failed: $errorCode")
            onStatusChanged("Scan failed: $errorCode")
        }
    }

    // STEP 2: Connect to TinyCircuits
    @SuppressLint("MissingPermission")
    private fun connectToPlant(device: BluetoothDevice) {

        onStatusChanged("Connecting...")

        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback
        )
    }

    // STEP 3: Handle connection and BLE data
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {

            if (
                status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {
                Log.d(TAG, "Connected. Discovering services...")
                onStatusChanged("Connected! Discovering services...")

                gatt.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                Log.d(TAG, "Disconnected. GATT status: $status")

                rxCharacteristic = null
                txCharacteristic = null

                if (bluetoothGatt == gatt) {
                    bluetoothGatt = null
                }

                gatt.close()

                // Notify the phone if the board was previously ready
                if (hasBeenReady) {
                    hasBeenReady = false
                    onConnectionEvent(false)
                }

                onStatusChanged("Disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatusChanged("Service discovery failed")
                return
            }

            Log.d(TAG, "========== DISCOVERED SERVICES ==========")

            for (service in gatt.services) {
                Log.d(TAG, "SERVICE: ${service.uuid}")

                for (characteristic in service.characteristics) {
                    Log.d(TAG, "CHARACTERISTIC: ${characteristic.uuid}")
                }
            }

            Log.d(TAG, "=========================================")

            val service = gatt.getService(SERVICE_UUID)

            if (service == null) {
                Log.e(TAG, "Expected UART service not found")
                onStatusChanged("BLE service not found")
                return
            }

            rxCharacteristic = service.getCharacteristic(RX_UUID)
            txCharacteristic = service.getCharacteristic(TX_UUID)

            if (rxCharacteristic == null || txCharacteristic == null) {
                onStatusChanged("BLE characteristics not found")
                return
            }

            // Enable notifications from the board
            val notificationEnabled = gatt.setCharacteristicNotification(
                txCharacteristic,
                true
            )

            if (!notificationEnabled) {
                onStatusChanged("Could not enable notifications")
                return
            }

            val descriptor = txCharacteristic!!.getDescriptor(CCCD_UUID)

            if (descriptor == null) {
                onStatusChanged("Notification descriptor not found")
                return
            }

            if (Build.VERSION.SDK_INT >= 33) {

                val result = gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )

                if (result != BluetoothStatusCodes.SUCCESS) {
                    onStatusChanged("Failed to enable notifications")
                }

            } else {

                @Suppress("DEPRECATION")
                descriptor.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                @Suppress("DEPRECATION")
                val result = gatt.writeDescriptor(descriptor)

                if (!result) {
                    onStatusChanged("Failed to enable notifications")
                }
            }
        }

        // STEP 3A: Notification setup completed
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {

            if (descriptor.uuid == CCCD_UUID) {

                if (status == BluetoothGatt.GATT_SUCCESS) {

                    Log.d(TAG, "Notifications enabled")

                    // Notify only once when the board becomes ready
                    if (!hasBeenReady) {
                        hasBeenReady = true
                        onConnectionEvent(true)
                    }

                    onStatusChanged("Connected and ready!")

                } else {
                    Log.e(TAG, "Notification setup failed: $status")
                    onStatusChanged("Notification setup failed")
                }
            }
        }

        // Android 13 and newer
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {

            if (characteristic.uuid == TX_UUID) {
                val message = value.toString(Charsets.UTF_8)

                Log.d(TAG, "Received from plant: $message")
                onMessageReceived(message)
            }
        }

        // Android 12 and older
        @Deprecated("Deprecated on Android 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {

            if (
                Build.VERSION.SDK_INT < 33 &&
                characteristic.uuid == TX_UUID
            ) {
                @Suppress("DEPRECATION")
                val message =
                    characteristic.value?.toString(Charsets.UTF_8) ?: ""

                Log.d(TAG, "Received from plant: $message")
                onMessageReceived(message)
            }
        }
    }

    // STEP 4: Send commands to TinyCircuits
    @SuppressLint("MissingPermission")
    fun sendToPlant(command: String) {

        val gatt = bluetoothGatt
        val characteristic = rxCharacteristic

        if (gatt == null || characteristic == null) {
            onStatusChanged("Plant is not connected")
            return
        }

        val data = command.toByteArray(Charsets.UTF_8)

        // Firmware uses CHAR_PROP_WRITE_WITHOUT_RESP
        if (Build.VERSION.SDK_INT >= 33) {

            val result = gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )

            if (result != BluetoothStatusCodes.SUCCESS) {
                onStatusChanged("Failed to send command")
            } else {
                Log.d(TAG, "Sent to plant: $command")
            }

        } else {

            @Suppress("DEPRECATION")
            characteristic.value = data

            @Suppress("DEPRECATION")
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            @Suppress("DEPRECATION")
            val result = gatt.writeCharacteristic(characteristic)

            if (!result) {
                onStatusChanged("Failed to send command")
            } else {
                Log.d(TAG, "Sent to plant: $command")
            }
        }
    }

    // STEP 5: Disconnect
    @SuppressLint("MissingPermission")
    fun disconnect() {

        if (isScanning) {
            scanner?.stopScan(scanCallback)
            isScanning = false
        }

        val gatt = bluetoothGatt

        if (gatt == null) {
            onStatusChanged("Disconnected")
            return
        }

        onStatusChanged("Disconnecting...")
        gatt.disconnect()
    }
}
