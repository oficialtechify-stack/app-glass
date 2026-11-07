package com.example.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.AppDatabase
import com.example.data.BleLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState()
}

/** Eventos vindos dos botões do óculos (grupo 0x12). */
enum class GlassesEvent {
    AiButtonPressed,
    RecordingStarted,
    RecordingStopped
}

/**
 * BLE real do óculos RDGlass AiMB-S1 (caminho 2 — sem o app nem o servidor do fabricante).
 *
 * Protocolo decifrado da captura ao vivo 05/11/2026 (19/19 frames validados, incl. áudio Opus):
 *   frame = [0xED] [0x40 cmd | 0x60 resp] [seq] [CRC8] [len BE] [grupo] [cmd] [payload...]
 *   total = 6 + len · CRC8 = poli 0x1D, init 0xFF, refin/refout, xorout 0x00, sobre grupo+cmd+payload
 *   serviço 0000fa00-… · notify 0000ea01-… · write 0000ea02-… · aux 0000ea05/ea06-…
 *   grupo 0x10 = sistema · grupo 0x12 = IA/gravação
 *
 * O que ainda NÃO existe aqui (lacunas honestas, ver ESPECIFICACAO.md §5):
 * - foto/vídeo: protocolo de câmera não mapeado — os stubs do ViewModel continuam honestos.
 * - SetTimeZone: payload de 10 bytes não decodificado — pulado na init, documentado no código.
 * - áudio de retorno (PCM): formato não provado — o Opus recebido é exposto cru via [opusAudio].
 */
@SuppressLint("MissingPermission")
class GlassBleManager(private val context: Context) {

    companion object {
        private const val TAG = "GlassBle"

        private val SERVICE_UUID =
            UUID.fromString("0000fa00-0000-1000-8000-00805f9b34fb")
        private val CHAR_NOTIFY_UUID =
            UUID.fromString("0000ea01-0000-1000-8000-00805f9b34fb")
        private val CHAR_WRITE_UUID =
            UUID.fromString("0000ea02-0000-1000-8000-00805f9b34fb")
        private val CHAR_AUX1_UUID =
            UUID.fromString("0000ea05-0000-1000-8000-00805f9b34fb")
        private val CHAR_AUX2_UUID =
            UUID.fromString("0000ea06-0000-1000-8000-00805f9b34fb")
        private val SERVICE_EXTRA_UUID =
            UUID.fromString("00003802-0000-1000-8000-00805f9b34fb")
        private val CHAR_EXTRA_UUID =
            UUID.fromString("00004a02-0000-1000-8000-00805f9b34fb")
        private val CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MTU_REQUERIDO = 517
        private const val TIMEOUT_ESCRITA_MS = 3000L
        private const val INTERVALO_KEEPALIVE_MS = 10_000L

        // Grupos / comandos (wire id = grupo + id). Só os observados ao vivo.
        private const val GRUPO_SISTEMA = 0x10
        private const val GRUPO_IA = 0x12
        private const val CMD_SET_SYSTEM = 0x01
        // CMD_SET_TIMEZONE = 0x02 — PULADO: payload de 10 bytes não decodificado.
        private const val CMD_GET_BATTERY = 0x03
        private const val CMD_GET_FIRMWARE = 0x04
        private const val CMD_ACTIVITY = 0x07
        private const val CMD_QUERY_OFFLINE_AUTH = 0x09
        private const val CMD_SET_OFFLINE_AS = 0x0B
        private const val CMD_TELL_INITIALIZED = 0x0D
        private const val CMD_GET_VIDEO_CONFIG = 0x0F
        private const val CMD_GET_AUDIO_CONFIG = 0x11
        private const val CMD_MEDIA_CHANGES = 0x03 // grupo 0x12
        private const val CMD_REQUEST_AI = 0x04 // grupo 0x12
        private const val CMD_RECORD_CONTROL = 0x05 // grupo 0x12
        private const val CMD_RECORD_DATA = 0x06 // grupo 0x12 (áudio Opus)
        private const val CMD_GET_MEDIA_TRIGGER = 0x0D // grupo 0x12

        /** Reflete os bits de um byte (parte do CRC8 refletido). */
        private fun refletir(b: Int): Int {
            var x = b and 0xFF
            var r = 0
            repeat(8) { r = (r shl 1) or (x and 1); x = x shr 1 }
            return r
        }

        /** CRC8 do protocolo: poli 0x1D, init 0xFF, refin/refout, xorout 0x00. */
        fun crc8(dados: ByteArray): Byte {
            var crc = 0xFF
            for (orig in dados) {
                val b = refletir(orig.toInt())
                crc = crc xor b
                repeat(8) {
                    crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x1D) and 0xFF
                    else (crc shl 1) and 0xFF
                }
            }
            return refletir(crc).toByte()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = AppDatabase.getDatabase(context)

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _connectionState =
        MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedDevices: StateFlow<List<BluetoothDevice>> = _scannedDevices.asStateFlow()

    /** Bateria do óculos (0–100), null enquanto não lida. */
    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    /** String de firmware (ex.: BBKG_GE135_MA05_G6_V1.09_H2.0_260715), null enquanto não lida. */
    private val _firmwareVersion = MutableStateFlow<String?>(null)
    val firmwareVersion: StateFlow<String?> = _firmwareVersion.asStateFlow()

    /** true enquanto o óculos grava (BKRecordControl status 01). */
    private val _glassesRecording = MutableStateFlow(false)
    val glassesRecording: StateFlow<Boolean> = _glassesRecording.asStateFlow()

    /** Eventos dos botões do óculos (1 clique no preto = assistente de voz). */
    private val _glassesEvent = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 16)
    val glassesEvent: SharedFlow<GlassesEvent> = _glassesEvent.asSharedFlow()

    /** Chunks de áudio Opus crus vindos do óculos (BKRecordData, sem os 2 bytes de cabeçalho). */
    private val _opusAudio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val opusAudio: SharedFlow<ByteArray> = _opusAudio.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var keepAliveJob: Job? = null
    private var initJob: Job? = null

    /** Contador de sequência do frame (byte [2]): o app incrementa 0,1,2… por frame enviado. */
    private val seqCounter = AtomicInteger(0)

    /** Completa quando o onCharacteristicWrite do frame atual chega. */
    private var pendingWrite: CompletableDeferred<Boolean>? = null

    /** Buffer de bytes do notify (frames podem chegar fragmentados). */
    private val rxBuffer = ArrayDeque<Byte>()

    // ------------------------------------------------------------------ scan

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val atual = _scannedDevices.value
            if (atual.none { it.address == device.address }) {
                _scannedDevices.value = atual + device
                Log.d(TAG, "achado: ${device.name} ${device.address}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan falhou: $errorCode")
            _connectionState.value = ConnectionState.Disconnected
            scope.launch {
                logBle("SISTEMA", "Busca BLE falhou (código $errorCode).", "error")
            }
        }
    }

    fun startScan() {
        if (!hasRequiredPermissions()) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning
        try {
            scanner.startScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan", e)
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "stopScan", e)
        }
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    // -------------------------------------------------------------- conexão

    fun connect(address: String) {
        if (!hasRequiredPermissions()) return
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        stopScan()
        _connectionState.value = ConnectionState.Connecting
        _batteryLevel.value = null
        _firmwareVersion.value = null
        _glassesRecording.value = false
        seqCounter.set(0)
        synchronized(rxBuffer) { rxBuffer.clear() }
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        keepAliveJob?.cancel()
        initJob?.cancel()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "disconnect", e)
        }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT conectado, descobrindo serviços")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT desconectado")
                keepAliveJob?.cancel()
                initJob?.cancel()
                _connectionState.value = ConnectionState.Disconnected
                scope.launch {
                    logBle("SISTEMA", "Óculos desconectado.", "info")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "descoberta de serviços falhou: $status")
                disconnect()
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "serviço fa00 não encontrado no óculos")
                scope.launch {
                    logBle("SISTEMA", "Serviço fa00 não encontrado — não é o AiMB-S1?", "error")
                }
                disconnect()
                return
            }
            notifyCharacteristic = service.getCharacteristic(CHAR_NOTIFY_UUID)
            writeCharacteristic = service.getCharacteristic(CHAR_WRITE_UUID)
            if (notifyCharacteristic == null || writeCharacteristic == null) {
                Log.e(TAG, "chars ea01/ea02 não encontradas")
                disconnect()
                return
            }
            // Liga o notify em ea01 (é por aqui que chegam comandos, eventos e áudio).
            gatt.setCharacteristicNotification(notifyCharacteristic, true)
            val cccd = notifyCharacteristic!!.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
            // ea05/ea06 e o serviço 3802 existem no original; papel desconhecido —
            // só lemos uma vez para registrar o que o óculos responde (ajuda a fase 4).
            service.getCharacteristic(CHAR_AUX1_UUID)?.let { gatt.readCharacteristic(it) }
            service.getCharacteristic(CHAR_AUX2_UUID)?.let { gatt.readCharacteristic(it) }
            gatt.getService(SERVICE_EXTRA_UUID)
                ?.getCharacteristic(CHAR_EXTRA_UUID)
                ?.let { gatt.readCharacteristic(it) }

            _connectionState.value = ConnectionState.Connected(
                deviceName = gatt.device.name ?: "AiMB-S1",
                deviceAddress = gatt.device.address
            )
            scope.launch {
                logBle("SISTEMA", "Conectado ao óculos. Negociando MTU 517…", "info")
            }
            gatt.requestMtu(MTU_REQUERIDO)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU: $mtu (status $status)")
            scope.launch {
                logBle("SISTEMA", "MTU negociado: $mtu.", "info")
                executarInit()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val dados = characteristic?.value ?: return
            if (characteristic.uuid == CHAR_NOTIFY_UUID) {
                alimentarRx(dados)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
            pendingWrite = null
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val dados = characteristic?.value ?: return
            Log.d(TAG, "leitura ${characteristic.uuid}: ${dados.toHex()}")
            scope.launch {
                logBle("LEITURA", "${characteristic.uuid}: ${dados.toHex()}", "info")
            }
        }
    }

    // ---------------------------------------------------------- frames ED…

    /** Monta um frame do protocolo. seq incrementa por frame enviado. */
    private fun montarFrame(grupo: Int, cmd: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val corpo = byteArrayOf(grupo.toByte(), cmd.toByte()) + payload
        val seq = (seqCounter.getAndIncrement() and 0xFF).toByte()
        val len = corpo.size
        return byteArrayOf(
            0xED.toByte(), 0x40, seq, crc8(corpo),
            (len shr 8).toByte(), (len and 0xFF).toByte()
        ) + corpo
    }

    /** Escreve um frame em ea02 e espera a confirmação de escrita (timeout 3 s). */
    private suspend fun escreverFrame(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeCharacteristic ?: return false
        val ok = withTimeoutOrNull(TIMEOUT_ESCRITA_MS) {
            val done = CompletableDeferred<Boolean>()
            pendingWrite = done
            @Suppress("DEPRECATION")
            c.value = frame
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val enviado = g.writeCharacteristic(c)
            if (!enviado) {
                pendingWrite = null
                return@withTimeoutOrNull false
            }
            done.await()
        } ?: false
        if (!ok) Log.w(TAG, "escrita sem confirmação: ${frame.toHex()}")
        return ok
    }

    /** Envia um comando (grupo/cmd/payload). Retorna false se não conseguiu escrever. */
    suspend fun enviarComando(grupo: Int, cmd: Int, payload: ByteArray = byteArrayOf()): Boolean {
        val frame = montarFrame(grupo, cmd, payload)
        Log.d(TAG, "→ ${frame.toHex()}")
        return escreverFrame(frame)
    }

    /** Alimenta o buffer de RX e extrai frames completos (valida tamanho + CRC8). */
    private fun alimentarRx(dados: ByteArray) {
        synchronized(rxBuffer) {
            dados.forEach { rxBuffer.addLast(it) }
            while (true) {
                // Sincroniza no magic 0xED.
                while (rxBuffer.isNotEmpty() && rxBuffer.first() != 0xED.toByte()) {
                    rxBuffer.removeFirst()
                }
                if (rxBuffer.size < 6) return
                val len = ((rxBuffer[4].toInt() and 0xFF) shl 8) or
                    (rxBuffer[5].toInt() and 0xFF)
                val total = 6 + len
                if (rxBuffer.size < total) return
                val frame = ByteArray(total) { rxBuffer.removeFirst() }
                val crcEsperado = frame[3]
                val crcCalculado = crc8(frame.copyOfRange(6, total))
                if (crcCalculado != crcEsperado) {
                    Log.w(TAG, "frame com CRC inválido, descartado: ${frame.toHex()}")
                    scope.launch {
                        logBle("BLE", "Frame descartado (CRC inválido): ${frame.toHex()}", "warning")
                    }
                    continue
                }
                tratarFrame(frame)
            }
        }
    }

    /** Interpreta um frame válido vindo do óculos. */
    private fun tratarFrame(frame: ByteArray) {
        val direcao = frame[1].toInt() and 0xFF
        val grupo = frame[6].toInt() and 0xFF
        val cmd = frame[7].toInt() and 0xFF
        val payload = frame.copyOfRange(8, frame.size)
        Log.d(TAG, "← ${frame.toHex()}")
        scope.launch {
            when (grupo) {
                GRUPO_SISTEMA -> when (cmd) {
                    CMD_GET_BATTERY -> {
                        if (payload.isNotEmpty()) {
                            val nivel = payload[0].toInt() and 0xFF
                            _batteryLevel.value = nivel
                            logBle("BATERIA", "Bateria do óculos: $nivel%.", "info")
                        }
                    }
                    CMD_GET_FIRMWARE -> {
                        val texto = payload.takeWhile { it in 0x20..0x7E }
                            .toByteArray().decodeToString()
                        if (texto.isNotBlank()) {
                            _firmwareVersion.value = texto
                            logBle("SISTEMA", "Firmware do óculos: $texto.", "info")
                        }
                    }
                    else -> logBle(
                        "BLE",
                        "resp sistema cmd=0x${cmd.toString(16)}: ${frame.toHex()}",
                        "info"
                    )
                }
                GRUPO_IA -> when (cmd) {
                    CMD_REQUEST_AI -> {
                        // Evento do botão preto (1 clique = assistente de voz).
                        if (direcao == 0x40) {
                            _glassesEvent.tryEmit(GlassesEvent.AiButtonPressed)
                            logBle("BOTÃO", "Assistente de voz acionado no óculos.", "info")
                            // O app original responde o evento com 12 04 00 00.
                            scope.launch { enviarComando(GRUPO_IA, CMD_REQUEST_AI, byteArrayOf(0, 0)) }
                        }
                    }
                    CMD_RECORD_CONTROL -> {
                        val gravando = payload.isNotEmpty() && payload[0].toInt() != 0
                        _glassesRecording.value = gravando
                        _glassesEvent.tryEmit(
                            if (gravando) GlassesEvent.RecordingStarted
                            else GlassesEvent.RecordingStopped
                        )
                        logBle("GRAVAÇÃO", if (gravando) "Gravação iniciada." else "Gravação parada.", "info")
                    }
                    CMD_RECORD_DATA -> {
                        // Payload: 2 bytes de cabeçalho + áudio Opus.
                        if (payload.size > 2) {
                            _opusAudio.tryEmit(payload.copyOfRange(2, payload.size))
                        }
                    }
                    else -> logBle(
                        "BLE",
                        "evento IA cmd=0x${cmd.toString(16)}: ${frame.toHex()}",
                        "info"
                    )
                }
                else -> logBle("BLE", "frame grupo=0x${grupo.toString(16)}: ${frame.toHex()}", "info")
            }
        }
    }

    // ------------------------------------------------- sequência de init

    /** Id deste pareamento (16 hex), gerado uma vez e guardado — NUNCA reutilizar o id da captura. */
    private fun deviceId(): String {
        val prefs = context.getSharedPreferences("rdglass_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    /**
     * Sequência de conexão observada no app original (05/11 08:53:26–29).
     * BKSetTimeZone é PULADO aqui: o payload de 10 bytes não foi decodificado
     * (observado: 00 00 01 A1 AB 8E 85 25 FD 00) — mandar bytes de outra sessão
     * seria fabricar dado. A init funciona sem ele; decodificar é tarefa da fase 2.
     */
    private suspend fun executarInit() {
        initJob = scope.launch {
            logBle("SISTEMA", "Iniciando sequência de conexão do óculos…", "info")
            val idBytes = deviceId().toByteArray(Charsets.US_ASCII)
            enviarComando(
                GRUPO_SISTEMA, CMD_SET_SYSTEM,
                byteArrayOf(0x01, 0x01) + idBytes + byteArrayOf(0x02, 0x70, 0x74)
            )
            // BKSetTimeZone pulado (ver comentário acima).
            enviarComando(GRUPO_SISTEMA, CMD_GET_BATTERY)
            // GetFirmwareInfo falhou 1× na captura original — 1 retry.
            if (!enviarComando(GRUPO_SISTEMA, CMD_GET_FIRMWARE)) {
                enviarComando(GRUPO_SISTEMA, CMD_GET_FIRMWARE)
            }
            enviarComando(GRUPO_IA, CMD_MEDIA_CHANGES)
            enviarComando(GRUPO_SISTEMA, CMD_GET_VIDEO_CONFIG)
            enviarComando(GRUPO_SISTEMA, CMD_GET_AUDIO_CONFIG)
            enviarComando(GRUPO_IA, CMD_GET_MEDIA_TRIGGER)
            enviarComando(GRUPO_SISTEMA, CMD_QUERY_OFFLINE_AUTH)
            enviarComando(GRUPO_SISTEMA, CMD_TELL_INITIALIZED)
            if (!enviarComando(GRUPO_SISTEMA, CMD_SET_OFFLINE_AS, byteArrayOf(0x00))) {
                enviarComando(GRUPO_SISTEMA, CMD_SET_OFFLINE_AS, byteArrayOf(0x00))
            }
            enviarComando(GRUPO_SISTEMA, CMD_ACTIVITY, byteArrayOf(0x01))
            logBle("SISTEMA", "Sequência de conexão enviada. Aguardando bateria/firmware…", "info")
            keepAliveJob = scope.launch {
                while (true) {
                    delay(INTERVALO_KEEPALIVE_MS)
                    if (!enviarComando(GRUPO_SISTEMA, CMD_ACTIVITY, byteArrayOf(0x01))) {
                        Log.w(TAG, "keep-alive falhou")
                    }
                }
            }
        }
    }

    /** Releitura manual de bateria (botão futuro / retry). */
    fun atualizarBateria() {
        scope.launch { enviarComando(GRUPO_SISTEMA, CMD_GET_BATTERY) }
    }

    // ------------------------------------------------------------- util

    fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(android.Manifest.permission.BLUETOOTH)
            permissions.add(android.Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private suspend fun logBle(tag: String, text: String, type: String) {
        try {
            db.bleLogDao().insertLog(BleLog(tag = tag, text = text, type = type))
        } catch (e: Exception) {
            Log.e(TAG, "logBle", e)
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
