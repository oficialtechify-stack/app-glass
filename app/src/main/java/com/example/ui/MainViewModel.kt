package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.Content as ApiContent
import com.example.api.GenerateContentRequest
import com.example.api.Part as ApiPart
import com.example.api.RetrofitClient
import com.example.api.SystemInstruction
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

    // Photo trigger
    fun triggerPhoto(byteVal: Byte) {
        viewModelScope.launch {
            // Send BLE pulse
            bleManager.sendByteCommand(byteVal)
            
            // Simulate capturing and saving photo to Album
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val title = "Foto Tirada " + dateFormat.format(Date())
            
            // Random index for visual design asset placeholder
            val photoId = (1..5).random()
            val dummyPath = "https://picsum.photos/seed/glass_photo_$photoId/400/300"
            
            db.albumDao().insertItem(
                AlbumItem(
                    title = title,
                    type = "photo",
                    filePath = dummyPath
                )
            )
            
            db.bleLogDao().insertLog(
                BleLog(
                    tag = "ALBUM",
                    text = "Foto adicionada ao Álbum com sucesso.",
                    type = "success"
                )
            )
        }
    }

    // Video record triggers
    fun startVideoRecording(byteVal: Byte) {
        viewModelScope.launch {
            bleManager.sendByteCommand(byteVal)
            _isRecording.value = true
            recordingStartTime = System.currentTimeMillis()
            
            db.bleLogDao().insertLog(
                BleLog(
                    tag = "GRAVAÇÃO",
                    text = "Gravação de vídeo iniciada.",
                    type = "info"
                )
            )
        }
    }

    fun stopVideoRecording(byteVal: Byte) {
        viewModelScope.launch {
            if (!_isRecording.value) return@launch
            bleManager.sendByteCommand(byteVal)
            _isRecording.value = false
            
            val durationMs = System.currentTimeMillis() - recordingStartTime
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / (1000 * 60)) % 60
            val durationString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val title = "Vídeo Gravado " + dateFormat.format(Date())
            
            val videoId = (1..5).random()
            val dummyPath = "https://picsum.photos/seed/glass_video_$videoId/400/300"

            db.albumDao().insertItem(
                AlbumItem(
                    title = title,
                    type = "video",
                    filePath = dummyPath,
                    duration = durationString
                )
            )
            
            db.bleLogDao().insertLog(
                BleLog(
                    tag = "ALBUM",
                    text = "Vídeo de $durationString adicionado ao Álbum.",
                    type = "success"
                )
            )
        }
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
                val apiKey = BuildConfig.GEMINI_API_KEY
                
                // Formulate system instructions specifically directing the identity matching Henrique's device
                val systemInstruction = SystemInstruction(
                    parts = listOf(
                        ApiPart(
                            text = "Você é o 'Assistente de IA' integrado aos óculos inteligentes modelo AiMB-S1 do Henrique. " +
                                    "Seja amigável, conciso e técnico. Suas respostas devem ser de preferência em português. " +
                                    "Você pode dar dicas sobre o funcionamento do óculos, descrever recursos de gravação e foto, " +
                                    "auxiliar com traduções, responder perguntas gerais de IA ou dar assistência contextual, sempre se dirigindo a ele como Henrique."
                        )
                    )
                )

                // Map context history to Gemini contents schema
                val contents = history.map { msg ->
                    val role = if (msg.sender == "user") "user" else "model"
                    ApiContent(role = role, parts = listOf(ApiPart(text = msg.text)))
                } + ApiContent(role = "user", parts = listOf(ApiPart(text = text)))

                val request = GenerateContentRequest(
                    contents = contents,
                    systemInstruction = systemInstruction
                )

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.geminiService.generateContent(apiKey, request)
                }

                val aiResponseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                    ?: "Desculpe Henrique, não consegui processar a resposta neste momento."

                db.chatMessageDao().insertMessage(
                    ChatMessage(sender = "ai", text = aiResponseText)
                )

                speak(aiResponseText)

            } catch (e: Exception) {
                db.chatMessageDao().insertMessage(
                    ChatMessage(
                        sender = "ai",
                        text = "Erro ao conectar-se com o cérebro da Inteligência Artificial: ${e.localizedMessage}. Por favor, verifique sua conexão com a internet."
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
