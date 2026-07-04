package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ble.ConnectionState
import com.example.data.AlbumItem
import com.example.data.BleLog
import com.example.data.ChatMessage
import com.example.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Custom modern Dark Theme colors matching the premium glasses branding (Immersive UI)
private val CarbonBackground = Color(0xFF111318)
private val SlateCard = Color(0xFF1D2024)
private val BorderGrey = Color(0xFF43474E)
private val TechBlue = Color(0xFFD0E4FF)
private val MintGreen = Color(0xFF10B981)
private val HotCoral = Color(0xFF910002)
private val GoldenYellow = Color(0xFFF59E0B)
private val LightGrey = Color(0xFFE2E2E6)
private val TextMuted = Color(0xFF8E9199)

private val AppDarkColorScheme = darkColorScheme(
    primary = TechBlue,
    secondary = MintGreen,
    tertiary = HotCoral,
    background = CarbonBackground,
    surface = SlateCard,
    onPrimary = Color(0xFF003258),
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = LightGrey,
    onSurface = LightGrey
)

enum class AppTab(val title: String, val iconFilled: ImageVector, val iconOutlined: ImageVector) {
    Home("Pagina inicial", Icons.Filled.Home, Icons.Outlined.Home),
    AI("AI", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    Album("Álbum de fo...", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
    Me("Meu", Icons.Filled.Person, Icons.Outlined.Person)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = AppDarkColorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    var currentTab by remember { mutableStateOf(AppTab.Home) }
    val context = LocalContext.current

    // Observe connection status to show global notifications
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    // Permission launcher
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    var hasGrantedPermissions by remember {
        mutableStateOf(viewModel.hasBlePermissions())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        hasGrantedPermissions = granted
        if (granted) {
            Toast.makeText(context, "Permissões de Bluetooth concedidas!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Permissões necessárias foram negadas.", Toast.LENGTH_LONG).show()
        }
    }

    // Trigger initial permission request if not granted
    LaunchedEffect(Unit) {
        if (!hasGrantedPermissions) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SlateCard,
                tonalElevation = 8.dp,
                modifier = Modifier.border(1.dp, BorderGrey, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            ) {
                AppTab.values().forEach { tab ->
                    val selected = currentTab == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (selected) tab.iconFilled else tab.iconOutlined,
                                contentDescription = tab.title,
                                tint = if (selected) TechBlue else TextMuted
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) LightGrey else TextMuted
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = TechBlue.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(CarbonBackground, Color(0xFF0F121A))
                    )
                )
        ) {
            if (!hasGrantedPermissions) {
                PermissionRequiredScreen {
                    permissionLauncher.launch(permissionsToRequest)
                }
            } else {
                when (currentTab) {
                    AppTab.Home -> HomeScreen(viewModel)
                    AppTab.AI -> AiChatScreen(viewModel)
                    AppTab.Album -> AlbumScreen(viewModel)
                    AppTab.Me -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
fun PermissionRequiredScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.BluetoothDisabled,
            contentDescription = "Bluetooth",
            tint = HotCoral,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Permissão de Bluetooth Necessária",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = LightGrey,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Para conectar, configurar e enviar comandos BLE para o seu óculos inteligente AiMB-S1, o aplicativo precisa de permissões de escaneamento Bluetooth e Localização.",
            fontSize = 14.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(containerColor = TechBlue),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("grant_permissions_button")
        ) {
            Text("Conceder Permissões", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

// ==================== SCREEN 1: HOME (MEUS ÓCULOS) ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val scannedDevices by viewModel.scannedDevices.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()

    var showScanDialog by remember { mutableStateOf(false) }

    // Byte command controls
    var selectedPhotoByte by remember { mutableStateOf(1.toByte()) }
    var photoByteCustomVal by remember { mutableStateOf("1") }
    var isCustomPhotoByteSelected by remember { mutableStateOf(false) }

    var videoStartByte by remember { mutableStateOf("2") }
    var videoStopByte by remember { mutableStateOf("3") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
    ) {
        // App title header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Meus óculos",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Smart Glass Controller",
                        fontSize = 14.sp,
                        color = TextMuted
                    )
                }
                
                // Pulsating status dot top right
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            if (connectionState is ConnectionState.Connected) MintGreen.copy(alpha = 0.15f)
                            else HotCoral.copy(alpha = 0.15f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (connectionState is ConnectionState.Connected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                        contentDescription = "Status",
                        tint = if (connectionState is ConnectionState.Connected) MintGreen else HotCoral,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Device Connection Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderGrey, RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (val state = connectionState) {
                        is ConnectionState.Disconnected -> {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = "Glass Disconnected",
                                tint = TextMuted,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Nenhum óculos conectado",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = LightGrey
                            )
                            Text(
                                text = "Inicie a busca para sincronizar o AiMB-S1",
                                fontSize = 12.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Button(
                                onClick = {
                                    viewModel.startBleScan()
                                    showScanDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = TechBlue),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("scan_connect_button")
                            ) {
                                Text("Conectar Óculos", fontWeight = FontWeight.Bold)
                            }
                        }
                        is ConnectionState.Scanning -> {
                            CircularProgressIndicator(
                                color = GoldenYellow,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Buscando óculos inteligentes...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = GoldenYellow
                            )
                            Text(
                                text = "Procurando por dispositivos com prefixo AiMB",
                                fontSize = 12.sp,
                                color = TextMuted,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Button(
                                onClick = { viewModel.stopBleScan() },
                                colors = ButtonDefaults.buttonColors(containerColor = HotCoral),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Parar Busca", fontWeight = FontWeight.Bold)
                            }
                        }
                        is ConnectionState.Connecting -> {
                            CircularProgressIndicator(
                                color = TechBlue,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Estabelecendo Conexão GATT...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TechBlue
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        is ConnectionState.Connected -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BluetoothConnected,
                                    contentDescription = "Glass Connected",
                                    tint = TechBlue,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = state.deviceName,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "MAC: " + state.deviceAddress,
                                        fontSize = 12.sp,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "Firmware: MA05_G6_V1.03_H2.0",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(MintGreen, CircleShape)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Conectado • Bateria 72%",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MintGreen
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            Button(
                                onClick = { viewModel.disconnectDevice() },
                                colors = ButtonDefaults.buttonColors(containerColor = HotCoral.copy(alpha = 0.2f)),
                                border = BorderStroke(1.dp, HotCoral.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Desconectar", color = HotCoral, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Active Commands Dashboard (enabled only after successful connection)
        item {
            Text(
                text = "Painel de Controle do Hardware",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item {
            val isConnected = connectionState is ConnectionState.Connected
            
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Quick Buttons (Grades "Gravação" e "Fotografar")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Video Record trigger card
                        Card(
                            onClick = {
                                if (isConnected) {
                                    val byteVal = videoStartByte.toIntOrNull()?.toByte() ?: 2.toByte()
                                    val stopVal = videoStopByte.toIntOrNull()?.toByte() ?: 3.toByte()
                                    if (isRecording) {
                                        viewModel.stopVideoRecording(stopVal)
                                    } else {
                                        viewModel.startVideoRecording(byteVal)
                                    }
                                }
                            },
                            enabled = isConnected,
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isRecording) MintGreen.copy(alpha = 0.15f) else SlateCard
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(92.dp)
                                .border(
                                    1.dp,
                                    if (isRecording) MintGreen else BorderGrey,
                                    RoundedCornerShape(16.dp)
                                )
                                .testTag("record_video_card")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            if (isRecording) MintGreen else MintGreen.copy(alpha = 0.1f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Videocam,
                                        contentDescription = "Video",
                                        tint = if (isRecording) Color.White else MintGreen,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (isRecording) "Gravando..." else "Gravação",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isRecording) MintGreen else Color.White
                                    )
                                    Text(
                                        text = if (isRecording) "Clique para parar" else "Iniciar vídeo",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        // Take Photo trigger card
                        Card(
                            onClick = {
                                if (isConnected) {
                                    val byteVal = if (isCustomPhotoByteSelected) {
                                        photoByteCustomVal.toIntOrNull()?.toByte() ?: 1.toByte()
                                    } else {
                                        selectedPhotoByte
                                    }
                                    viewModel.triggerPhoto(byteVal)
                                }
                            },
                            enabled = isConnected,
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            modifier = Modifier
                                .weight(1f)
                                .height(92.dp)
                                .border(1.dp, BorderGrey, RoundedCornerShape(16.dp))
                                .testTag("take_photo_card")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(HotCoral.copy(alpha = 0.1f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoCamera,
                                        contentDescription = "Photo",
                                        tint = HotCoral,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Fotografar",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Disparar câmera",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }

                    // Advanced parameters customization
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderGrey, RoundedCornerShape(16.dp))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Ajustar Bytes de Comunicação",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TechBlue
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // Photo Byte Select
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Comando Foto:", fontSize = 12.sp, color = TextMuted, modifier = Modifier.width(100.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    RadioButton(
                                        selected = !isCustomPhotoByteSelected,
                                        onClick = { isCustomPhotoByteSelected = false },
                                        colors = RadioButtonDefaults.colors(selectedColor = TechBlue)
                                    )
                                    Text("0x01", fontSize = 12.sp, color = LightGrey)

                                    RadioButton(
                                        selected = isCustomPhotoByteSelected,
                                        onClick = { isCustomPhotoByteSelected = true },
                                        colors = RadioButtonDefaults.colors(selectedColor = TechBlue)
                                    )
                                    Text("Custom", fontSize = 12.sp, color = LightGrey)

                                    if (isCustomPhotoByteSelected) {
                                        TextField(
                                            value = photoByteCustomVal,
                                            onValueChange = { photoByteCustomVal = it },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = CarbonBackground,
                                                unfocusedContainerColor = CarbonBackground,
                                                focusedIndicatorColor = Color.Transparent,
                                                unfocusedIndicatorColor = Color.Transparent
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .width(70.dp)
                                                .height(36.dp)
                                                .padding(horizontal = 4.dp),
                                            textStyle = TextStyle(fontSize = 12.sp, color = Color.White)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Video Bytes Start/Stop Inputs
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Vídeo Iniciar:", fontSize = 12.sp, color = TextMuted, modifier = Modifier.width(100.dp))
                                TextField(
                                    value = videoStartByte,
                                    onValueChange = { videoStartByte = it },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = CarbonBackground,
                                        unfocusedContainerColor = CarbonBackground,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .width(64.dp)
                                        .height(36.dp),
                                    textStyle = TextStyle(fontSize = 12.sp, color = Color.White)
                                )

                                Spacer(modifier = Modifier.width(16.dp))

                                Text("Parar:", fontSize = 12.sp, color = TextMuted)
                                Spacer(modifier = Modifier.width(8.dp))
                                TextField(
                                    value = videoStopByte,
                                    onValueChange = { videoStopByte = it },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = CarbonBackground,
                                        unfocusedContainerColor = CarbonBackground,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .width(64.dp)
                                        .height(36.dp),
                                    textStyle = TextStyle(fontSize = 12.sp, color = Color.White)
                                )
                            }
                        }
                    }
                }

                // Blurred overlays for disabled states
                if (!isConnected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Conecte seus óculos para liberar comandos.",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }

        // Live Console Terminal Log section
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderGrey, RoundedCornerShape(16.dp))
                    .background(SlateCard, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(GoldenYellow, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Console de Log em Tempo Real",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LightGrey
                        )
                    }
                    Text(
                        text = "Limpar",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HotCoral,
                        modifier = Modifier
                            .clickable { viewModel.clearLogs() }
                            .padding(4.dp)
                            .testTag("clear_logs_button")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "Nenhum log gravado ainda.",
                            fontSize = 11.sp,
                            color = TextMuted,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(logs) { log ->
                                LogLine(log)
                            }
                        }
                    }
                }
            }
        }

        // User Manual Scrollable Cards section (Instruções de Uso)
        item {
            Text(
                text = "Instruções de uso",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    ManualCard(
                        title = "Aprenda a tirar foto",
                        desc = "Operação detalhada\nIntrodução sobre tirar f...",
                        icon = Icons.Default.Camera,
                        tint = HotCoral
                    )
                }
                item {
                    ManualCard(
                        title = "Gestos trackpad",
                        desc = "Reproduzir / pausar música,\ncortar músicas, etc...",
                        icon = Icons.Default.Gesture,
                        tint = TechBlue
                    )
                }
                item {
                    ManualCard(
                        title = "Assistente de voz",
                        desc = "Clique no botão preto\npara ativar o assistente...",
                        icon = Icons.Default.Mic,
                        tint = GoldenYellow
                    )
                }
                item {
                    ManualCard(
                        title = "Tradução da IA",
                        desc = "Traduzir em IA a qualquer\nhora, em qualquer lugar...",
                        icon = Icons.Default.Translate,
                        tint = MintGreen
                    )
                }
            }
        }
    }

    // Bluetooth Devices Scanning Dialog
    if (showScanDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.stopBleScan()
                showScanDialog = false
            },
            title = {
                Text("Óculos Encontrados (BLE)", color = Color.White)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Sincronizando com dispositivos iniciando com 'AiMB'...",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (scannedDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = TechBlue)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                        ) {
                            items(scannedDevices) { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.connectDevice(device.address)
                                            showScanDialog = false
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Bluetooth,
                                            contentDescription = "Glasses",
                                            tint = TechBlue,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = device.name ?: "Óculos Inteligente",
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = device.address,
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "Connect",
                                        tint = TextMuted
                                    )
                                }
                                Divider(color = BorderGrey)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.stopBleScan()
                        showScanDialog = false
                    }
                ) {
                    Text("Cancelar", color = HotCoral)
                }
            },
            containerColor = SlateCard,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun LogLine(log: BleLog) {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeStr = formatter.format(Date(log.timestamp))

    val color = when (log.type) {
        "success" -> MintGreen
        "warning" -> GoldenYellow
        "error" -> HotCoral
        "rx" -> Color(0xFF38BDF8)
        "tx" -> Color(0xFFC084FC)
        else -> TechBlue
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "[$timeStr] ",
            fontSize = 11.sp,
            color = Color(0xFF556073),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
        Text(
            text = "${log.tag}: ${log.text}",
            fontSize = 11.sp,
            color = color,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            lineHeight = 14.sp
        )
    }
}

@Composable
fun ManualCard(
    title: String,
    desc: String,
    icon: ImageVector,
    tint: Color
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        modifier = Modifier
            .width(160.dp)
            .height(200.dp)
            .border(1.dp, BorderGrey, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(tint.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    fontSize = 11.sp,
                    color = TextMuted,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


// ==================== SCREEN 2: AI CHAT (DIÁLOGO AI) ====================

@Composable
fun AiChatScreen(viewModel: MainViewModel) {
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isChatLoading.collectAsStateWithLifecycle()
    var textInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // Chat Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Diálogo ai",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Assistente de IA integrado",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
            IconButton(
                onClick = { viewModel.clearChatHistory() }
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Limpar conversa",
                    tint = HotCoral
                )
            }
        }

        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(messages) { message ->
                ChatMessageRow(message)
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(TechBlue, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Digitando...",
                            color = TextMuted,
                            fontSize = 13.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
            }
        }

        // Send text bar row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Falar com o assistente do óculos...", color = TextMuted, fontSize = 14.sp) },
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, BorderGrey, RoundedCornerShape(24.dp))
                    .testTag("ai_chat_input"),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SlateCard,
                    unfocusedContainerColor = SlateCard,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    keyboardType = KeyboardType.Text
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (textInput.isNotBlank() && !isLoading) {
                            viewModel.sendMessageToAI(textInput)
                            textInput = ""
                        }
                    }
                )
            )

            FloatingActionButton(
                onClick = {
                    if (textInput.isNotBlank() && !isLoading) {
                        viewModel.sendMessageToAI(textInput)
                        textInput = ""
                    }
                },
                containerColor = TechBlue,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("send_ai_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ChatMessageRow(message: ChatMessage) {
    val isUser = message.sender == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(TechBlue.copy(alpha = 0.2f), CircleShape)
                    .border(1.dp, TechBlue, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "AI Assistant",
                    tint = TechBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
        }

        Card(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) TechBlue else SlateCard
            ),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .border(
                    1.dp,
                    if (isUser) Color.Transparent else BorderGrey,
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
        ) {
            Text(
                text = message.text,
                fontSize = 14.sp,
                color = Color.White,
                modifier = Modifier.padding(12.dp),
                lineHeight = 18.sp
            )
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(GoldenYellow.copy(alpha = 0.2f), CircleShape)
                    .border(1.dp, GoldenYellow, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = "Henrique",
                    tint = GoldenYellow,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}


// ==================== SCREEN 3: ALBUM ====================

@Composable
fun AlbumScreen(viewModel: MainViewModel) {
    val items by viewModel.albumItems.collectAsStateWithLifecycle()
    var selectedItemForPreview by remember { mutableStateOf<AlbumItem?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // Album Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Álbum de fotos",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Fotos e vídeos capturados pelos óculos",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }

        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.NoPhotography,
                    contentDescription = "Sem mídia",
                    tint = TextMuted,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Nenhuma mídia capturada ainda",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = LightGrey
                )
                Text(
                    text = "Pressione 'Disparar Foto' ou inicie uma Gravação na tela inicial",
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(items) { item ->
                    AlbumItemCard(item, onPreview = { selectedItemForPreview = item })
                }
            }
        }
    }

    // Media Preview Overlay Dialog
    selectedItemForPreview?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedItemForPreview = null },
            title = {
                Text(
                    text = item.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(Color.Black, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = item.filePath,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (item.type == "video") {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Tipo: ${if (item.type == "photo") "Fotografia" else "Gravação de Vídeo"}",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                    if (item.type == "video") {
                        Text(
                            text = "Duração: ${item.duration}",
                            color = MintGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            viewModel.deleteAlbumItem(item.id)
                            selectedItemForPreview = null
                        }
                    ) {
                        Text("Deletar", color = HotCoral)
                    }
                    TextButton(
                        onClick = { selectedItemForPreview = null }
                    ) {
                        Text("Fechar", color = TechBlue)
                    }
                }
            },
            containerColor = SlateCard,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun AlbumItemCard(item: AlbumItem, onPreview: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .border(1.dp, BorderGrey, RoundedCornerShape(16.dp))
            .clickable { onPreview() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = item.filePath,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Blur dark gradient at bottom for text readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
            )

            // Video badge indicator top right
            if (item.type == "video") {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Video",
                        tint = MintGreen,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = item.duration,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = "Photo",
                    tint = HotCoral,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(6.dp)
                        .size(12.dp)
                )
            }

            // Title bottom details
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}


// ==================== SCREEN 4: MY/SETTINGS (MEU) ====================

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // Settings Header
        Text(
            text = "Meu perfil",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        // User profile Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderGrey, RoundedCornerShape(20.dp))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(GoldenYellow.copy(alpha = 0.2f), CircleShape)
                        .border(1.dp, GoldenYellow, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = "Avatar",
                        tint = GoldenYellow,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Henrique", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Gênero: Masculino", fontSize = 12.sp, color = TextMuted)
                    Text("Aniversário: 08/08/2007", fontSize = 12.sp, color = TextMuted)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Options settings list matching the screen image
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, BorderGrey, RoundedCornerShape(20.dp))
        ) {
            Column {
                SettingsListItem(
                    icon = Icons.Default.Book,
                    tint = TechBlue,
                    title = "Manual de óculos",
                    subtitle = "Manual de óculos"
                )
                Divider(color = BorderGrey, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsListItem(
                    icon = Icons.Default.VolumeUp,
                    tint = GoldenYellow,
                    title = "Voz AI",
                    subtitle = "Instruções do modo de poupança d..."
                )
                Divider(color = BorderGrey, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsListItem(
                    icon = Icons.Default.Feedback,
                    tint = HotCoral,
                    title = "Feedback",
                    subtitle = "Detalhes de opiniões específicas a..."
                )
                Divider(color = BorderGrey, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsListItem(
                    icon = Icons.Default.CloudDownload,
                    tint = MintGreen,
                    title = "Versão do aplicativo",
                    subtitle = "Atualizar para a versão mais recente"
                )
                Divider(color = BorderGrey, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsListItem(
                    icon = Icons.Default.Info,
                    tint = TextMuted,
                    title = "Sobre",
                    subtitle = "Informações sobre o app"
                )
            }
        }
    }
}

@Composable
fun SettingsListItem(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(tint.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
                Text(subtitle, fontSize = 11.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "Acessar",
            tint = TextMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}
