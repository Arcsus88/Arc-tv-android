package com.arcsus.arcdisney.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcsus.arcdisney.ui.DisneyViewModel.Debrid
import com.arcsus.arcdisney.ui.theme.Night
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.DateFormat
import java.util.Date

/**
 * Signing this device in to its own AllDebrid account, for full-speed
 * streaming. A PIN (and a QR code with it in) to enter from another phone or
 * computer, since a locked device can't browse to a login page.
 */
@Composable
fun DebridScreen(
    state: Debrid,
    setup: Boolean,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!setup) BackHandler(onBack = onBack)
    Column(
        modifier
            .fillMaxSize()
            .background(Night.Deep)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(if (setup) 28.dp else 8.dp))
        if (setup) {
            Text("Faster streaming", color = Night.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "Back", onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Night.Text) }
                Spacer(Modifier.width(6.dp))
                Text("AllDebrid", color = Night.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Sign in to your own AllDebrid account and films start in seconds, at full speed. " +
                "Without it they stream by torrent, which is slower.",
            color = Night.Muted,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(20.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (state) {
                Debrid.SignedOut -> {
                    SetupButton("Sign in with AllDebrid", onClick = onSignIn)
                    Spacer(Modifier.height(10.dp))
                    Text("Needs an AllDebrid subscription. The sign-in stays on this device.", color = Night.Muted, fontSize = 13.sp)
                }
                Debrid.GettingPin -> SetupCard {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Night.Text, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Getting a code from AllDebrid…", color = Night.Text)
                    }
                }
                is Debrid.WaitingForPin -> SetupCard {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        QrCode(state.pin.qrUrl, Modifier.size(180.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Scan this with your phone, or go to ${state.pin.url.removePrefix("https://").trimEnd('/')} and enter",
                            color = Night.Muted,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.pin.pin,
                            color = Night.Text,
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 6.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Night.Muted, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Waiting for the code to be entered", color = Night.Muted, fontSize = 13.sp)
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.padding(top = 6.dp)) { Text("Cancel", color = Night.Muted) }
                    }
                }
                is Debrid.SignedIn -> SetupCard {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4ADE80))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            val who = state.account?.username?.ifBlank { null }
                            Text(if (who != null) "Signed in as $who" else "Signed in", color = Night.Text, fontSize = 16.sp)
                            state.account?.let { a ->
                                Text(
                                    if (a.premium && a.premiumUntil > 0) {
                                        "Premium until " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.premiumUntil * 1000))
                                    } else if (a.premium) {
                                        "Premium"
                                    } else {
                                        "No premium: AllDebrid needs a subscription to stream"
                                    },
                                    color = if (a.premium) Night.Muted else Color(0xFFFF8A80),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    TextButton(onClick = onSignOut, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
                        Text("Sign out", color = Night.Muted)
                    }
                }
                is Debrid.Failed -> {
                    SetupNote(state.message)
                    Spacer(Modifier.height(12.dp))
                    SetupButton("Try again", onClick = onSignIn)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val signedIn = state is Debrid.SignedIn
        if (setup) {
            SetupButton(if (signedIn) "Continue" else "Skip for now", enabled = true, onClick = onContinue)
            Text(
                "You can sign in later by long-pressing the update button.",
                color = Night.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        } else {
            SetupButton("Done", onClick = onBack)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** [text] as a QR code: black on white, with the quiet zone scanners need. */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bitmap = remember(text) {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = "QR code for the AllDebrid sign-in",
        filterQuality = FilterQuality.None,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color.White),
    )
}
