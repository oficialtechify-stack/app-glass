package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.ChatTurn
import com.example.api.ChatRequest
import com.example.api.RetrofitClient
import com.example.ble.ConnectionState
import com.example.ble.GlassBleManager
import com.example.data.AlbumItem
import com.example.data.AppDatabase
import com.example.data.BleLog
import com.example.data.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.speech.tts.TextToSpeech

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val bleManager = GlassBleManager(application)

    // Reactive streams from database
    val logs: StateFlow<List<BleLog>> = db.bleLogDao().getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albumItems: StateFlow<List<AlbumItem>> = db.albumDao().getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chatMessages: StateFlow<List<ChatMessage>> = db.chatMessageDao().getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // BLE manager flows
    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    val scannedDevices = bleManager.scannedDevices

    // AI Chat auxiliary state
    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

    // Recording status tracking for video
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var recordingStartTime: Long = 0

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    init {
        try {
            tts = TextToSpeech(application) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale("pt", "BR"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale("pt"))
                    }
                    isTtsReady = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Pre-populate database with some helpful instructions or mock files if empty
        viewModelScope.launch {
            db.bleLogDao().insertLog(
                BleLog(
                    tag = "SISTEMA",
                    text = "Controlador Inicializado. Bluetooth pronto.",
                    type = "info"
                )
            )
            
            // Populate chat with an initial welcoming assistant message if empty
            db.chatMessageDao().getAllMessages().collect { list ->
                if (list.isEmpty()) {
                    db.chatMessageDao().insertMessage(
                        ChatMessage(
                            sender = "ai",
                            text = "Olá Henrique! Sou o Assistente de IA dos seus óculos inteligentes AiMB-S1. Como posso ajudar você hoje?"
                        )
                    )
                }
            }
        }
    }

    // BLE Methods
    fun startBleScan() {
        bleManager.startScan()
    }

    fun stopBleScan() {
        bleManager.stopScan()
    }

    fun connectDevice(address: String) {
        bleManager.connect(address)
    }

    fun disconnectDevice() {
        bleManager.disconnect()
    }

    fun hasBlePermissions(): Boolean {
        return bleManager.hasRequiredPermissions()
    }

    // Proprietary camera commands have not been validated against RDGlass.
    // Never fabricate photos or videos in the user's album.
    fun triggerPhoto(byteVal: Byte) {
        viewModelScope.launch {
            db.bleLogDao().insertLog(BleLog(tag = "CÂMERA", text = "Captura indisponível: protocolo da câmera ainda não validado. Nenhuma foto foi criada.", type = "warning"))
        }
    }

    fun startVideoRecording(byteVal: Byte) {
        viewModelScope.launch {
            _isRecording.value = false
            db.bleLogDao().insertLog(BleLog(tag = "GRAVAÇÃO", text = "Gravação indisponível: protocolo de vídeo ainda não validado.", type = "warning"))
        }
    }

    fun stopVideoRecording(byteVal: Byte) {
        _isRecording.value = false
    }

    // Database cleaner utilities
    fun clearLogs() {
        viewModelScope.launch {
            db.bleLogDao().clearLogs()
            db.bleLogDao().insertLog(
                BleLog(
                    tag = "LOGS",
                    text = "Console de log limpo com sucesso.",
                    type = "info"
                )
            )
        }
    }

    fun deleteAlbumItem(id: Int) {
        viewModelScope.launch {
            db.albumDao().deleteItem(id)
            db.bleLogDao().insertLog(
                BleLog(
                    tag = "ALBUM",
                    text = "Mídia deletada do álbum.",
                    type = "info"
                )
            )
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            db.chatMessageDao().clearChat()
            db.chatMessageDao().insertMessage(
                ChatMessage(
                    sender = "ai",
                    text = "Conversa reiniciada. Henrique, como posso ajudar você e seus óculos inteligentes agora?"
                )
            )
        }
    }

    // AI Chat - Gemini REST logic
    fun sendMessageToAI(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            // Save User Message
            val userMsg = ChatMessage(sender = "user", text = text)
            db.chatMessageDao().insertMessage(userMsg)
            _isChatLoading.value = true

            // Fetch history from DB for conversational context
            val history = db.chatMessageDao().getAllMessages().stateIn(viewModelScope).value
            
            try {
                val messages = history
                    .filter { it.sender == "user" || it.sender == "ai" }
                    .takeLast(19)
                    .map { ChatTurn(if (it.sender == "user") "user" else "assistant", it.text) }
                    .toMutableList()
                if (messages.lastOrNull()?.content != text) {
                    messages.add(ChatTurn("user", text))
                }
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.glassAiService.chat(ChatRequest(messages))
                }
                val aiResponseText = response.reply

                db.chatMessageDao().insertMessage(
                    ChatMessage(sender = "ai", text = aiResponseText)
                )

                speak(aiResponseText)

            } catch (e: Exception) {
                db.chatMessageDao().insertMessage(
                    ChatMessage(
                        sender = "ai",
                        text = "Não foi possível consultar a IA (${e.localizedMessage}). Confira a configuração do servidor."
                    )
                )
                db.bleLogDao().insertLog(
                    BleLog(
                        tag = "SISTEMA",
                        text = "Falha no contato com o servidor de IA: ${e.message}",
                        type = "error"
                    )
                )
            } finally {
                _isChatLoading.value = false
            }
        }
    }

    fun speak(text: String) {
        if (isTtsReady) {
            try {
                val cleanText = text
                    .replace(Regex("[*#_`]"), "") // Remove Markdown formatting
                    .replace(Regex("<[^>]*>"), "") // Remove HTML-like tags
                tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "AiChatResponse")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
