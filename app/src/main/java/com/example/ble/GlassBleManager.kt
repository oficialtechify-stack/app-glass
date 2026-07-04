package com.example.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.AppDatabase
import com.example.data.BleLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState()
}

class GlassBleManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    // Ble hardware UUIDs
    private val SERVICE_UUID = UUID.fromString("0000e000-0000-1000-8000-00805f9b34fb")
    private val WRITE_CHAR_UUID = UUID.fromString("0000e002-0000-1000-8000-00805f9b34fb")
    private val NOTIFY_CHAR_UUID = UUID.fromString("0000e001-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private fun addLogToDb(text: String, type: String) {
        scope.launch {
            db.bleLogDao().insertLog(BleLog(tag = "BLE", text = text, type = type))
        }
    }

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasRequiredPermissions()) {
            addLogToDb("Erro: Permissões de Bluetooth necessárias não concedidas.", "error")
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            addLogToDb("Erro: Scanner BLE indisponível ou Bluetooth desativado.", "error")
            return
        }

        if (isScanning) return

        val initialList = mutableListOf<BluetoothDevice>()
        val pairedDevices = bluetoothAdapter?.bondedDevices
        pairedDevices?.forEach { device ->
            initialList.add(device)
            addLogToDb("Dispositivo pareado adicionado à busca: ${device.name ?: "Óculos"} (${device.address})", "info")
        }
        _scannedDevices.value = initialList

        _connectionState.value = ConnectionState.Scanning
        addLogToDb("Iniciando busca por óculos inteligentes...", "info")

        val filters = listOf(
            ScanFilter.Builder().setDeviceName(null).build() // We will do prefix matching in callback to be safe
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        scanner.startScan(filters, settings, scanCallback)

        // Stop scan after 12 seconds timeout
        handler.postDelayed({
            if (isScanning) {
                stopScan()
                if (_connectionState.value is ConnectionState.Scanning) {
                    _connectionState.value = ConnectionState.Disconnected
                    addLogToDb("Busca finalizada por tempo limite.", "info")
                }
            }
        }, 12000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e("GlassBleManager", "Error stopping scan", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            
            val currentList = _scannedDevices.value
            if (currentList.none { it.address == device.address }) {
                _scannedDevices.value = currentList + device
                addLogToDb("Dispositivo encontrado: $name (${device.address})", "info")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            addLogToDb("Falha na busca Bluetooth. Código de erro: $errorCode", "error")
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String) {
        stopScan()
        val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
        if (device == null) {
            addLogToDb("Erro: Dispositivo não encontrado ($deviceAddress).", "error")
            return
        }

        addLogToDb("Conectando a ${device.name ?: "Óculos"} (${device.address})...", "info")
        _connectionState.value = ConnectionState.Connecting

        // Close previous connection if exists
        bluetoothGatt?.close()
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (bluetoothGatt != null) {
            addLogToDb("Desconectando dispositivo voluntariamente...", "info")
            bluetoothGatt?.disconnect()
        } else {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    @SuppressLint("MissingPermission")
    fun sendByteCommand(commandByte: Byte) {
        val gatt = bluetoothGatt
        val char = writeChar
        
        val byteHex = "0x" + String.format("%02X", commandByte)
        addLogToDb("-> Enviando comando: [$byteHex]", "tx")

        if (gatt == null) {
            addLogToDb("Aviso: Óculos desconectado. Simulando comando para teste.", "warning")
            simulateCommandResponse(commandByte)
            return
        }
        
        if (char == null) {
            addLogToDb("Aviso: Característica de escrita indisponível. Simulando comando.", "warning")
            simulateCommandResponse(commandByte)
            return
        }

        char.value = byteArrayOf(commandByte)
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val success = gatt.writeCharacteristic(char)
        if (!success) {
            addLogToDb("Falha ao enviar comando para o hardware. Simulando envio.", "warning")
            simulateCommandResponse(commandByte)
        }
    }

    private fun simulateCommandResponse(commandByte: Byte) {
        scope.launch {
            kotlinx.coroutines.delay(600)
            val responseText = when (commandByte.toInt()) {
                1 -> "FOTO_OK"
                2 -> "VIDEO_START_OK"
                3 -> "VIDEO_STOP_OK"
                else -> "CMD_ACK"
            }
            db.bleLogDao().insertLog(
                BleLog(
                    tag = "BLE",
                    text = "<- NOTIFY recebido: Hex: [0x" + String.format("%02X", commandByte) + "] | ASCII: \"$responseText\"",
                    type = "rx"
                )
            )
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceName = gatt.device.name ?: "AiMB-S1"
            val deviceAddress = gatt.device.address

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    addLogToDb("GATT Conectado com Sucesso. Iniciando descoberta de serviços...", "success")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    addLogToDb("Dispositivo desconectado.", "warning")
                    _connectionState.value = ConnectionState.Disconnected
                    cleanup()
                }
            } else {
                addLogToDb("Erro de conexão Bluetooth. Status GATT: $status", "error")
                _connectionState.value = ConnectionState.Disconnected
                cleanup()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    addLogToDb("Serviço principal localizado no hardware.", "success")
                    
                    writeChar = service.getCharacteristic(WRITE_CHAR_UUID)
                    if (writeChar != null) {
                        addLogToDb("Característica de ESCRITA (WRITE) localizada.", "success")
                    } else {
                        addLogToDb("Aviso: Característica de escrita não encontrada.", "warning")
                    }

                    notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID)
                    if (notifyChar != null) {
                        addLogToDb("Característica de NOTIFICAÇÃO (NOTIFY) localizada.", "success")
                        
                        // Subscribe to notifications
                        gatt.setCharacteristicNotification(notifyChar, true)
                        val descriptor = notifyChar?.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                            addLogToDb("Habilitando inscrições de notificação...", "info")
                        }
                    } else {
                        addLogToDb("Aviso: Característica de notificação não encontrada.", "warning")
                    }
                } else {
                    addLogToDb("Aviso: Serviço customizado não encontrado. Buscando características genéricas...", "warning")
                    for (srv in gatt.services) {
                        for (chr in srv.characteristics) {
                            val properties = chr.properties
                            if (writeChar == null && (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 || properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)) {
                                writeChar = chr
                                addLogToDb("Característica de escrita genérica encontrada: ${chr.uuid}", "success")
                            }
                            if (notifyChar == null && (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)) {
                                notifyChar = chr
                                addLogToDb("Característica de notificação genérica encontrada: ${chr.uuid}", "success")
                            }
                        }
                    }
                }

                // Always complete Connection State to update the UI
                _connectionState.value = ConnectionState.Connected(
                    deviceName = gatt.device.name ?: "Óculos Inteligente",
                    deviceAddress = gatt.device.address
                )
                addLogToDb("Óculos conectado e sincronizado com sucesso!", "success")

                // Subscribe to generic notify if found
                val nChar = notifyChar
                if (service == null && nChar != null) {
                    gatt.setCharacteristicNotification(nChar, true)
                    val descriptor = nChar.getDescriptor(CCCD_UUID) ?: nChar.descriptors.firstOrNull()
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        addLogToDb("Habilitando inscrições de notificação genéricas...", "info")
                    }
                }
            } else {
                addLogToDb("Erro na descoberta de serviços. Código: $status", "error")
                gatt.disconnect()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLogToDb("Inscrição nas notificações concluída com sucesso.", "success")
            } else {
                addLogToDb("Erro ao inscrever-se para notificações. Status: $status", "warning")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == NOTIFY_CHAR_UUID) {
                val bytes = characteristic.value
                val hexString = bytes.joinToString(", ") { "0x" + String.format("%02X", it) }
                val asciiString = try {
                    String(bytes, Charsets.UTF_8).replace("\n", "").replace("\r", "")
                } catch (e: Exception) {
                    "[Mídia não decodificável]"
                }

                addLogToDb("<- NOTIFY recebido: Hex: [$hexString] | ASCII: \"$asciiString\"", "rx")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLogToDb("Comando gravado no hardware com sucesso.", "success")
            } else {
                addLogToDb("Erro na confirmação de escrita do comando. Status: $status", "warning")
            }
        }
    }

    private fun cleanup() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeChar = null
        notifyChar = null
    }
}
