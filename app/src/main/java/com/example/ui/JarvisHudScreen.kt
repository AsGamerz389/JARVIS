package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.JarvisBorder
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisCyanBright
import com.example.ui.theme.JarvisCyanDark
import com.example.ui.theme.JarvisDarkBackground
import com.example.ui.theme.JarvisErrorRed
import com.example.ui.theme.JarvisListeningCyan
import com.example.ui.theme.JarvisSpeakingGreen
import com.example.ui.theme.JarvisSurfaceDark
import com.example.ui.theme.JarvisSurfaceElevated
import com.example.ui.theme.JarvisTextMuted
import com.example.ui.theme.JarvisTextPrimary
import com.example.ui.theme.JarvisTextSecondary
import com.example.ui.theme.JarvisWarningAmber
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun JarvisHudScreen(
    viewModel: JarvisViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showTerminal by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    // Permission launcher for Microphone and Contacts
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            viewModel.startListening()
        }
    }

    val requestPermissionsAndStart = {
        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val contactsGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val needed = mutableListOf<String>()
        if (!micGranted) needed.add(Manifest.permission.RECORD_AUDIO)
        if (!contactsGranted) needed.add(Manifest.permission.READ_CONTACTS)

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            viewModel.onPowerOrMicClicked()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisDarkBackground),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = JarvisDarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP HEADER BAR
            JarvisTopBar(
                state = uiState.assistantState,
                onSpeakerTestClick = { viewModel.runSpeakerTest() },
                onToggleTerminal = { showTerminal = !showTerminal },
                isTestingSpeaker = uiState.isSpeakerTesting
            )

            // SCROLLABLE CORE CONTENT
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // ARC REACTOR HOLOGRAPHIC CORE
                ArcReactorCore(
                    state = uiState.assistantState,
                    audioLevel = uiState.audioLevel,
                    onClick = { requestPermissionsAndStart() },
                    modifier = Modifier.testTag("arc_reactor_core")
                )

                Spacer(modifier = Modifier.height(16.dp))

                // STATUS PILL
                JarvisStatusPill(
                    state = uiState.assistantState,
                    message = uiState.statusMessage
                )

                Spacer(modifier = Modifier.height(12.dp))

                // LIVE CONVERSATION & ASSISTANT SPEECH CARD
                JarvisTranscriptCard(
                    userTranscript = uiState.currentTranscript,
                    assistantResponse = uiState.assistantResponse,
                    assistantState = uiState.assistantState,
                    onInterrupt = { viewModel.interruptPlayback() }
                )

                // CONTACT SELECTION DISAMBIGUATION CARD (IF MULTIPLE MATCHES)
                if (uiState.contactChoices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = JarvisSurfaceElevated),
                        border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "MULTIPLE CONTACT MATCHES IDENTIFIED:",
                                color = JarvisCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            uiState.contactChoices.forEach { contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewModel.selectContactToCall(contact) }
                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(contact.name, color = JarvisTextPrimary, fontWeight = FontWeight.Medium)
                                        Text(contact.phoneNumber, color = JarvisTextSecondary, fontSize = 12.sp)
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = "Call ${contact.name}",
                                        tint = JarvisCyan
                                    )
                                }
                            }
                        }
                    }
                }

                // OPTIONAL HUD TERMINAL LOGS
                AnimatedVisibility(visible = showTerminal) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF03060E))
                            .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "// TELEMETRY & ACTION LOGS",
                            color = JarvisCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        uiState.terminalLogs.takeLast(6).forEach { log ->
                            Text(
                                text = log,
                                color = JarvisTextSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // QUICK DIRECTIVE CHIPS
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val directives = listOf(
                    "WhatsApp kholo" to "openWhatsApp",
                    "Call Mom" to "callMom",
                    "Open YouTube" to "openYouTube",
                    "Status check" to "statusCheck",
                    "Hindi mein baat karo" to "talkHindi",
                    "Test Speaker" to "testSpeaker"
                )
                items(directives) { (label, action) ->
                    AssistChip(
                        onClick = {
                            when (action) {
                                "testSpeaker" -> viewModel.runSpeakerTest()
                                else -> viewModel.handleUserInput(label)
                            }
                        },
                        label = { Text(label, fontSize = 12.sp, color = JarvisTextPrimary) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = JarvisSurfaceDark,
                            labelColor = JarvisTextPrimary
                        ),
                        border = BorderStroke(1.dp, JarvisBorder),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }

            // BOTTOM CONTROL ROW: Fallback Input + Main Voice Power Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Speak or enter command, sir...", color = JarvisTextMuted, fontSize = 13.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("command_text_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = JarvisSurfaceDark,
                        unfocusedContainerColor = JarvisSurfaceDark,
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = JarvisBorder,
                        focusedTextColor = JarvisTextPrimary,
                        unfocusedTextColor = JarvisTextPrimary
                    ),
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (textInput.isNotBlank()) {
                                viewModel.handleUserInput(textInput)
                                textInput = ""
                            }
                        }
                    ),
                    trailingIcon = {
                        if (textInput.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    viewModel.handleUserInput(textInput)
                                    textInput = ""
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send Command",
                                    tint = JarvisCyan
                                )
                            }
                        }
                    }
                )

                // MAIN VOICE / POWER BUTTON
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            when (uiState.assistantState) {
                                AssistantState.LISTENING -> JarvisListeningCyan
                                AssistantState.SPEAKING -> JarvisSpeakingGreen
                                AssistantState.CONNECTING -> JarvisWarningAmber
                                AssistantState.ERROR -> JarvisErrorRed
                                AssistantState.IDLE -> JarvisCyan
                            }
                        )
                        .clickable { requestPermissionsAndStart() }
                        .testTag("main_mic_power_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (uiState.assistantState) {
                            AssistantState.LISTENING -> Icons.Default.Mic
                            AssistantState.SPEAKING -> Icons.Default.GraphicEq
                            else -> Icons.Default.Mic
                        },
                        contentDescription = "Activate Jarvis Voice",
                        tint = JarvisDarkBackground,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun JarvisTopBar(
    state: AssistantState,
    onSpeakerTestClick: () -> Unit,
    onToggleTerminal: () -> Unit,
    isTestingSpeaker: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            AssistantState.LISTENING -> JarvisListeningCyan
                            AssistantState.SPEAKING -> JarvisSpeakingGreen
                            AssistantState.CONNECTING -> JarvisWarningAmber
                            AssistantState.ERROR -> JarvisErrorRed
                            AssistantState.IDLE -> JarvisCyan
                        }
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "JARVIS",
                    color = JarvisTextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "AI VOICE CO-PILOT // ONLINE",
                    color = JarvisCyanBright,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Test Speaker Diagnostic Button (Section 15 & 44)
            OutlinedButton(
                onClick = onSpeakerTestClick,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isTestingSpeaker) JarvisCyan.copy(alpha = 0.2f) else JarvisSurfaceDark,
                    contentColor = JarvisCyan
                ),
                border = BorderStroke(1.dp, if (isTestingSpeaker) JarvisCyanBright else JarvisBorder),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("test_speaker_button")
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeUp,
                    contentDescription = "Test Speaker",
                    modifier = Modifier.size(14.dp),
                    tint = JarvisCyan
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isTestingSpeaker) "TESTING..." else "TEST 440Hz",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Terminal logs toggle
            IconButton(
                onClick = onToggleTerminal,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Toggle Terminal Logs",
                    tint = JarvisTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ArcReactorCore(
    state: AssistantState,
    audioLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "arc_reactor_anim")

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_rotation"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "inner_rotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val activeColor by animateColorAsState(
        targetValue = when (state) {
            AssistantState.LISTENING -> JarvisListeningCyan
            AssistantState.SPEAKING -> JarvisSpeakingGreen
            AssistantState.CONNECTING -> JarvisWarningAmber
            AssistantState.ERROR -> JarvisErrorRed
            AssistantState.IDLE -> JarvisCyan
        },
        label = "active_color"
    )

    Box(
        modifier = modifier
            .size(220.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2f) * 0.85f

            // Dynamic expansion based on audio level
            val dynamicRadius = baseRadius * (if (state == AssistantState.LISTENING || state == AssistantState.SPEAKING) {
                1f + (audioLevel * 0.25f)
            } else {
                pulseScale
            })

            // 1. Soft Ambient Outer Glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeColor.copy(alpha = 0.35f),
                        activeColor.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = dynamicRadius * 1.3f
                ),
                radius = dynamicRadius * 1.3f,
                center = center
            )

            // 2. Outer Dashed HUD Ring (Rotating clockwise)
            val outerStroke = Stroke(
                width = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(25f, 15f), outerRotation)
            )
            drawCircle(
                color = activeColor.copy(alpha = 0.5f),
                radius = dynamicRadius,
                center = center,
                style = outerStroke
            )

            // 3. Middle Segmented Reactor Ring (Rotating counter-clockwise)
            val segCount = 12
            val segAngle = 360f / segCount
            for (i in 0 until segCount) {
                val startAngle = (innerRotation + (i * segAngle)) % 360f
                drawArc(
                    color = activeColor.copy(alpha = 0.8f),
                    startAngle = startAngle,
                    sweepAngle = segAngle * 0.6f,
                    useCenter = false,
                    topLeft = Offset(center.x - dynamicRadius * 0.75f, center.y - dynamicRadius * 0.75f),
                    size = Size(dynamicRadius * 1.5f, dynamicRadius * 1.5f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // 4. Central Reactor Core Glow Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        activeColor,
                        activeColor.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = dynamicRadius * 0.45f
                ),
                radius = dynamicRadius * 0.45f,
                center = center
            )

            // 5. High-tech Crosshairs / Triangular Accents
            val crosshairLength = 12.dp.toPx()
            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = Offset(center.x - crosshairLength, center.y),
                end = Offset(center.x + crosshairLength, center.y),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = Color.White.copy(alpha = 0.8f),
                start = Offset(center.x, center.y - crosshairLength),
                end = Offset(center.x, center.y + crosshairLength),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Center Icon Indicator
        Icon(
            imageVector = when (state) {
                AssistantState.LISTENING -> Icons.Default.Mic
                AssistantState.SPEAKING -> Icons.Default.GraphicEq
                AssistantState.CONNECTING -> Icons.Default.PowerSettingsNew
                else -> Icons.Default.PowerSettingsNew
            },
            contentDescription = "Core",
            tint = JarvisDarkBackground,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
fun JarvisStatusPill(state: AssistantState, message: String) {
    val stateColor = when (state) {
        AssistantState.LISTENING -> JarvisListeningCyan
        AssistantState.SPEAKING -> JarvisSpeakingGreen
        AssistantState.CONNECTING -> JarvisWarningAmber
        AssistantState.ERROR -> JarvisErrorRed
        AssistantState.IDLE -> JarvisCyan
    }

    Surface(
        color = JarvisSurfaceElevated,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, stateColor.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(stateColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message.uppercase(),
                color = stateColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun JarvisTranscriptCard(
    userTranscript: String,
    assistantResponse: String,
    assistantState: AssistantState,
    onInterrupt: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("jarvis_speech_card"),
        colors = CardDefaults.cardColors(containerColor = JarvisSurfaceDark),
        border = BorderStroke(1.dp, JarvisBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // If user has spoken
            if (userTranscript.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        color = JarvisSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, JarvisBorder)
                    ) {
                        Text(
                            text = "\"$userTranscript\"",
                            color = JarvisTextPrimary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Jarvis Response
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(JarvisCyan.copy(alpha = 0.15f))
                        .border(1.dp, JarvisCyan, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("J", color = JarvisCyanBright, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "JARVIS",
                        color = JarvisCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = assistantResponse,
                        color = JarvisTextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    // Interruption affordance when speaking
                    if (assistantState == AssistantState.SPEAKING) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onInterrupt,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisErrorRed),
                            border = BorderStroke(1.dp, JarvisErrorRed.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("STOP / INTERRUPT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
