package com.example.auraplantapp

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID

class PlantBluetoothManager(
    private val context: Context,
    private val onMessageReceived: (String) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager

    private val adapter = bluetoothManager.adapter
    private val scanner = adapter.bluetoothLeScanner

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    companion object {
        val SERVICE_UUID: UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        val RX_UUID: UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

        val TX_UUID: UUID =
            UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    }

    // STEP 1: Scan for BLE devices
    @SuppressLint("MissingPermission")
    fun scanForPlant() {

        onStatusChanged("Scanning for plant...")

        scanner?.startScan(object : ScanCallback() {

            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                val device = result.device

                onStatusChanged(
                    "Found device: ${device.name ?: device.address}"
                )

                scanner.stopScan(this)

                connectToPlant(device)
            }

            override fun onScanFailed(errorCode: Int) {
                onStatusChanged("Scan failed: $errorCode")
            }
        })
    }

    // STEP 2: Connect to the BLE device
    @SuppressLint("MissingPermission")
    private fun connectToPlant(device: BluetoothDevice) {

        onStatusChanged("Connecting...")

        bluetoothGatt = device.connectGatt(
            context,
            false,
            gattCallback
        )
    }

    // STEP 3: Handle connection and incoming data
    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED
            ) {

                onStatusChanged("Connected! Discovering services...")

                gatt.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                onStatusChanged("Disconnected")

                rxCharacteristic = null
                txCharacteristic = null
                gatt.close()
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

            val service = gatt.getService(SERVICE_UUID)
            Log.d("AuraPlantBLE", "========== DISCOVERED SERVICES ==========")

            for (service in gatt.services) {
                Log.d("AuraPlantBLE", "SERVICE: ${service.uuid}")

                for (characteristic in service.characteristics) {
                    Log.d(
                        "AuraPlantBLE",
                        "CHARACTERISTIC: ${characteristic.uuid}"
                    )
                }
            }

            Log.d("AuraPlantBLE", "=========================================")
            if (service == null) {
                onStatusChanged("BLE service not found")
                return
            }

            rxCharacteristic =
                service.getCharacteristic(RX_UUID)

            txCharacteristic =
                service.getCharacteristic(TX_UUID)

            if (rxCharacteristic == null || txCharacteristic == null) {
                onStatusChanged("BLE characteristics not found")
                return
            }

            // Enable notifications
            gatt.setCharacteristicNotification(
                txCharacteristic,
                true
            )

            val descriptor = txCharacteristic!!
                .getDescriptor(
                    UUID.fromString(
                        "00002902-0000-1000-8000-00805f9b34fb"
                    )
                )

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
                gatt.writeDescriptor(descriptor)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onStatusChanged("Connected and ready!")
            } else {
                onStatusChanged("Notification setup failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == TX_UUID) {
                val message = value.toString(Charsets.UTF_8)
                onMessageReceived(message)
            }
        }

        @Deprecated("Deprecated on Android 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < 33 &&
                characteristic.uuid == TX_UUID
            ) {
                val message =
                    characteristic.value?.toString(Charsets.UTF_8) ?: ""

                onMessageReceived(message)
            }
        }
    }

    // STEP 4: Send a command to the plant
    @SuppressLint("MissingPermission")
    fun sendToPlant(command: String) {

        val gatt = bluetoothGatt
        val characteristic = rxCharacteristic

        if (gatt == null || characteristic == null) {
            onStatusChanged("Plant is not connected")
            return
        }

        val data = command.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= 33) {
            val result = gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )

            if (result != BluetoothStatusCodes.SUCCESS) {
                onStatusChanged("Failed to send command")
            }
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = data

            @Suppress("DEPRECATION")
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    // STEP 5: Disconnect
    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }
}
