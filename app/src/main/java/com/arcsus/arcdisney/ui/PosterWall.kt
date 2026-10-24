package com.arcsus.arcdisney.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arcsus.arcdisney.R
import com.arcsus.arcdisney.data.Film
import com.arcsus.arcdisney.ui.theme.Night

/**
 * Every playable film as a wall of posters, with nothing above it but a
 * small Disney logo, the search bar and the update button.
 */
@Composable
fun PosterWall(
    films: List<Film>,
    query: String,
    onQuery: (String) -> Unit,
    update: DisneyViewModel.Update,
    message: String?,
    online: Boolean,
    onUpdate: () -> Unit,
    onOpenWifi: () -> Unit,
    onAllDebrid: () -> Unit,
    onTmdbKey: () -> Unit,
    onPlay: (Film) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = rememberLazyGridState()
    LaunchedEffect(query) { grid.scrollToItem(0) }
    var menu by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize().background(Night.Deep)) {
        // The splash's blue, lingering at the top.
        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(Brush.verticalGradient(listOf(Night.Sky.copy(alpha = 0.55f), Color.Transparent))),
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_disney),
                    contentDescription = "Disney",
                    modifier = Modifier.height(24.dp).aspectRatio(476f / 200f),
                )
                Spacer(Modifier.width(12.dp))
                SearchField(query, onQuery, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                UpdateButton(
                    busy = update != DisneyViewModel.Update.Idle,
                    onClick = onUpdate,
                    onLongClick = { menu = true },
                )
            }
            UpdateProgress(update)
            if (films.isEmpty()) {
                Text(
                    if (query.isBlank()) "No films yet. Tap update to look for some." else "No films match “${query.trim()}”.",
                    color = Night.Muted,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 80.dp, start = 32.dp, end = 32.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    state = grid,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 6.dp,
                        bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(films, key = { it.id }) { film -> Poster(film, onPlay) }
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = message != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                Pill(message.orEmpty())
            }
            // A locked device has no Settings app: this is the way back to Wi-Fi.
            AnimatedVisibility(visible = !online, enter = fadeIn(), exit = fadeOut()) {
                Pill("No internet \u00B7 Wi-Fi settings", onClick = onOpenWifi)
            }
        }

        SettingsMenu(
            open = menu,
            onClose = { menu = false },
            items = listOf("Wi-Fi" to onOpenWifi, "AllDebrid account" to onAllDebrid, "TMDB key" to onTmdbKey),
        )
    }
}

/**
 * The update button's long-press menu. It's drawn in the wall itself, not as
 * a popup window: on a phone, a popup opening under the finger that's still
 * pressing the button only opened the first time. A tap anywhere else, or
 * Back, closes it without reaching the posters underneath.
 */
@Composable
private fun BoxScope.SettingsMenu(open: Boolean, onClose: () -> Unit, items: List<Pair<String, () -> Unit>>) {
    if (open) {
        BackHandler(onBack = onClose)
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } })
    }
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
        exit = fadeOut(tween(90)),
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 60.dp, end = 14.dp),
    ) {
        Column(
            Modifier
                .width(220.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF16204A))
                .border(1.dp, Night.Line, RoundedCornerShape(14.dp))
                .padding(vertical = 6.dp),
        ) {
            items.forEach { (label, action) ->
                Text(
                    label,
                    color = Night.Text,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onClose()
                            action()
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    Row(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Night.Field)
            .border(1.dp, Night.Line, RoundedCornerShape(23.dp))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Night.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Search Disney films", color = Night.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Night.Text, fontSize = 16.sp),
                cursorBrush = SolidColor(Night.Text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Clear search") { onQuery("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Night.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = Night.Text,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF0182352))
            .border(1.dp, Night.Line, RoundedCornerShape(22.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/** Tap: look for new films. Long-press: the few settings a locked device needs. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpdateButton(busy: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Night.Field)
            .border(1.dp, Night.Line, CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onClickLabel = "Look for new films",
                onLongClickLabel = "Settings",
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = "Look for new films",
            tint = Night.Text,
            modifier = Modifier.size(22.dp).rotate(if (busy) angle else 0f),
        )
    }
}

@Composable
private fun UpdateProgress(update: DisneyViewModel.Update) {
    AnimatedVisibility(visible = update != DisneyViewModel.Update.Idle) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            val searching = update as? DisneyViewModel.Update.Searching
            if (searching != null && searching.total > 0) {
                LinearProgressIndicator(
                    progress = { searching.done.toFloat() / searching.total },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Night.Text,
                    trackColor = Night.Line,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Night.Text,
                    trackColor = Night.Line,
                )
            }
            Text(
                when {
                    update is DisneyViewModel.Update.FindingFilms -> "Looking for new Disney films…"
                    searching != null && searching.total > 0 -> "Searching for releases · ${searching.done} of ${searching.total}"
                    else -> "Checking releases…"
                },
                color = Night.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Poster(film: Film, onPlay: (Film) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    Box(
        Modifier
            .aspectRatio(2f / 3f)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(Night.Card)
            .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClickLabel = "Play ${film.title}") { onPlay(film) },
        contentAlignment = Alignment.Center,
    ) {
        // The title shows until (or instead of) the poster.
        Text(
            film.title,
            color = Night.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(10.dp),
        )
        if (film.poster.isNotBlank()) {
            AsyncImage(
                model = film.posterUrl(),
                contentDescription = film.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
