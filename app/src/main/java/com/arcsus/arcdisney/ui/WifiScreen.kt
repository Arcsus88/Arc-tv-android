package com.arcsus.arcdisney.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcsus.arcdisney.ui.theme.Night
import com.arcsus.arcdisney.wifi.Security
import com.arcsus.arcdisney.wifi.WifiController
import com.arcsus.arcdisney.wifi.WifiNetwork
import com.arcsus.arcdisney.wifi.WifiState
import kotlinx.coroutines.delay

/**
 * The Wi-Fi screen bound to the device: scans while it's open, asks for the
 * location access Android 9 needs to list networks, and joins the one picked.
 */
@Composable
fun WifiRoute(
    controller: WifiController,
    state: WifiState,
    setup: Boolean,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(controller) {
        controller.startScanning()
        onDispose { controller.stopScanning() }
    }
    // Fresh results now and then (Android 9 throttles anything faster).
    LaunchedEffect(controller) {
        while (true) {
            delay(15_000)
            controller.scan()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.permissionChanged()
    }
    val askForLocation = {
        permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    // Ask straight away, once: on a locked device there's no other way to grant it.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.locationPermission) {
        if (!state.locationPermission && !asked && controller.canConnectInApp) {
            asked = true
            askForLocation()
        }
    }
    if (!setup) BackHandler(onBack = onBack)

    WifiScreen(
        state = state,
        setup = setup,
        canConnectInApp = controller.canConnectInApp,
        onTurnOn = controller::turnOn,
        onAllowLocation = askForLocation,
        onConnect = controller::connect,
        onOpenSystemWifi = {
            val action = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
        onContinue = onContinue,
        onSkip = onSkip,
        onBack = onBack,
    )
}

private const val OTHER = "\u0000other"

/** A plain list of nearby networks; tap one, type its password, Connect. */
@Composable
fun WifiScreen(
    state: WifiState,
    setup: Boolean,
    canConnectInApp: Boolean,
    onTurnOn: () -> Unit,
    onAllowLocation: () -> Unit,
    onConnect: (ssid: String, password: String, security: Security) -> Unit,
    onOpenSystemWifi: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canList = state.locationPermission && state.locationOn
    // Without a list the only way in is by name, so open that straight away.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(canList, state.enabled) {
        if (!canList && state.enabled && open == null) open = OTHER
    }
    // Collapse the row once its network is joined.
    LaunchedEffect(state.connectedSsid) {
        if (open != null && open == state.connectedSsid) open = null
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Night.Deep)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(if (setup) 28.dp else 8.dp))
        if (setup) {
            Text("Connect to Wi-Fi", color = Night.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Arc Disney needs the internet to find and play films.", color = Night.Muted, fontSize = 15.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Night.Text) }
                Spacer(Modifier.width(6.dp))
                Text("Wi-Fi", color = Night.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(18.dp))
        Status(state)
        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f)) {
            when {
                !canConnectInApp -> Column {
                    Note("This version of Android doesn't let apps join Wi-Fi themselves. Use the system's Wi-Fi panel.")
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Open Wi-Fi settings", onClick = onOpenSystemWifi)
                }
                !state.enabled -> Column {
                    Note("Wi-Fi is off.")
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Turn on Wi-Fi", onClick = onTurnOn)
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.locationPermission) {
                        item {
                            Note(
                                "Android only lists nearby networks for apps allowed to use location. " +
                                    "Allow it to see them, or add your network by name below.",
                                action = "Allow" to onAllowLocation,
                            )
                        }
                    } else if (!state.locationOn) {
                        item { Note("Location is turned off on this device, so nearby networks can't be listed. Add your network by name below.") }
                    } else if (state.networks.isEmpty()) {
                        item { Note("Looking for networks…") }
                    }
                    items(state.networks, key = { it.ssid }) { network ->
                        NetworkRow(
                            network = network,
                            open = open == network.ssid,
                            connected = network.ssid == state.connectedSsid,
                            connecting = network.ssid == state.connecting,
                            error = state.failed?.takeIf { it.first == network.ssid }?.second,
                            onClick = {
                                when {
                                    network.ssid == state.connectedSsid -> Unit
                                    !network.security.supported -> open = network.ssid
                                    network.security.needsPassword -> open = if (open == network.ssid) null else network.ssid
                                    else -> onConnect(network.ssid, "", network.security)
                                }
                            },
                            onConnect = { password -> onConnect(network.ssid, password, network.security) },
                        )
                    }
                    item(key = OTHER) {
                        OtherNetworkRow(
                            open = open == OTHER,
                            connecting = state.connecting,
                            failed = state.failed,
                            onClick = { open = if (open == OTHER) null else OTHER },
                            onConnect = { ssid, password ->
                                onConnect(ssid, password, if (password.isEmpty()) Security.OPEN else Security.PSK)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (setup) {
            PrimaryButton(if (state.online) "Continue" else "Continue once connected", enabled = state.online, onClick = onContinue)
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Skip for now", color = Night.Muted)
            }
        } else {
            PrimaryButton("Done", onClick = onBack)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Status(state: WifiState) {
    val (text, good) = when {
        state.connecting != null -> "Connecting to ${state.connecting}…" to false
        state.connectedSsid != null && state.online -> "Connected to ${state.connectedSsid}" to true
        state.connectedSsid != null -> "Connected to ${state.connectedSsid} · no internet yet" to false
        state.online -> "Online" to true
        else -> "Not connected" to false
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (good) Color(0xFF4ADE80) else Night.Muted.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = Night.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NetworkRow(
    network: WifiNetwork,
    open: Boolean,
    connected: Boolean,
    connecting: Boolean,
    error: String?,
    onClick: () -> Unit,
    onConnect: (String) -> Unit,
) {
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (connected) null else "Join ${network.ssid}", onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalBars(network.level)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(network.ssid, color = Night.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    connected -> "Connected"
                    connecting -> "Connecting…"
                    error != null -> error
                    network.security == Security.SAE -> "WPA3 only · not supported on this device"
                    network.security == Security.EAP -> "Work network · not supported here"
                    else -> null
                }
                if (sub != null) {
                    Text(
                        sub,
                        color = when {
                            connected -> Color(0xFF4ADE80)
                            error != null -> Color(0xFFFF8A80)
                            else -> Night.Muted
                        },
                        fontSize = 13.sp,
                    )
                }
            }
            when {
                connecting -> CircularProgressIndicator(Modifier.size(20.dp), color = Night.Text, strokeWidth = 2.dp)
                connected -> Icon(Icons.Default.Check, contentDescription = "Connected", tint = Color(0xFF4ADE80))
                network.security != Security.OPEN -> Icon(
                    Icons.Default.Lock,
                    contentDescription = "Needs a password",
                    tint = Night.Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        AnimatedVisibility(open && network.security.needsPassword && !connected) {
            PasswordEntry(network.security.minPassword, connecting, onConnect)
        }
    }
}

@Composable
private fun OtherNetworkRow(
    open: Boolean,
    connecting: String?,
    failed: Pair<String, String>?,
    onClick: () -> Unit,
    onConnect: (ssid: String, password: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Add a network by name", onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Other network…", color = Night.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Field(name, { name = it }, "Network name", ImeAction.Next)
                Spacer(Modifier.height(10.dp))
                Field(password, { password = it }, "Password (leave empty if none)", ImeAction.Done, secret = true) {
                    focus.clearFocus()
                    if (name.isNotBlank()) onConnect(name.trim(), password)
                }
                failed?.takeIf { it.first == name.trim() }?.let {
                    Text(it.second, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(12.dp))
                val busy = connecting != null && connecting == name.trim()
                PrimaryButton(
                    if (busy) "Connecting…" else "Connect",
                    enabled = name.isNotBlank() && !busy && (password.isEmpty() || password.length >= 8),
                ) {
                    focus.clearFocus()
                    onConnect(name.trim(), password)
                }
            }
        }
    }
}

@Composable
private fun PasswordEntry(minLength: Int, connecting: Boolean, onConnect: (String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val ready = password.length >= minLength && !connecting
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Field(password, { password = it }, "Password", ImeAction.Done, secret = true) {
            if (ready) {
                focus.clearFocus()
                onConnect(password)
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (connecting) "Connecting…" else "Connect", enabled = ready) {
            focus.clearFocus()
            onConnect(password)
        }
    }
}

@Composable
private fun Field(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    ime: ImeAction,
    secret: Boolean = false,
    onDone: () -> Unit = {},
) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            imeAction = ime,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = if (secret) {
            { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show", color = Night.Muted) } }
        } else {
            null
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Night.Text,
            unfocusedBorderColor = Night.Line,
            focusedLabelColor = Night.Text,
            cursorColor = Night.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Night.Card)
            .border(1.dp, Night.Line, RoundedCornerShape(14.dp)),
    ) { content() }
}

@Composable
private fun Note(text: String, action: Pair<String, () -> Unit>? = null) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = Night.Muted, fontSize = 14.sp)
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) { Text(label, color = Night.Text) }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Night.Text,
            contentColor = Night.Deep,
            disabledContainerColor = Night.Field,
            disabledContentColor = Night.Muted,
        ),
        modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).height(50.dp),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/** Four rising bars, [level] of them lit (always at least one: it's in range). */
@Composable
private fun SignalBars(level: Int) {
    Canvas(Modifier.size(width = 20.dp, height = 16.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                color = if (i < level.coerceAtLeast(1)) Night.Text else Night.Line,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}
