package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PersonCard(p: Person, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "person")
    Column(
        modifier.width(116.dp).graphicsLayer { scaleX = s; scaleY = s }
            .onFocusChanged { f = it.isFocused }.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(104.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Surf2, Surf)))
                .border(3.dp, if (f) Accent2 else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (p.photo != null) AsyncImage(p.photo, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(raw = true, text = p.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString("").uppercase(), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Text(raw = true, text = p.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        p.role?.let { Text(raw = true, text = it, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
    }
}

/**
 * Merkt, welche Karte (Besetzung „c:ID“ / ähnlicher Titel „s:ID“) auf der Detailseite zuletzt angeklickt wurde, damit
 * Zurück dort landet statt wieder bei „Fortsetzen“. Wird beim Wiederöffnen der Seite einmal gelesen und gelöscht.
 */
object DetailFocus {
    val last = HashMap<String, String>()
}

// Innenabstand der Reihen: die vergrößerte markierte Karte wird sonst am Rand der Reihe angeschnitten
private val RowEdge = 16.dp

@Composable
fun CastRow(
    cast: List<Person>, state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    restoreId: Int? = null, restoreReq: FocusRequester? = null, onPerson: (Person) -> Unit
) {
    if (cast.isEmpty()) return
    Column(Modifier.padding(top = 30.dp)) {
        Text("Besetzung", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            Modifier.offset(x = -RowEdge), state = state,
            contentPadding = PaddingValues(horizontal = RowEdge, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(cast.distinctBy { it.id }, key = { it.id }) { p ->
                PersonCard(p, { onPerson(p) }, if (restoreReq != null && p.id == restoreId) Modifier.focusRequester(restoreReq) else Modifier)
            }
        }
    }
}

@Composable
fun SimilarRow(
    title: String, list: List<Item>, state: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    restoreId: String? = null, restoreReq: FocusRequester? = null, onOpen: (Item) -> Unit
) {
    if (list.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            Modifier.offset(x = -RowEdge), state = state,
            contentPadding = PaddingValues(horizontal = RowEdge, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(list.distinctBy { it.id }, key = { it.id }) { i ->
                PosterCard(i, { onOpen(i) }, Modifier.width(150.dp).then(if (restoreReq != null && i.id == restoreId) Modifier.focusRequester(restoreReq) else Modifier))
            }
        }
    }
}

/** Seite einer Person: Foto, Biografie und alle ihre Titel, die in der eigenen Playlist vorkommen. */
@Composable
fun PersonScreen(ref: PersonRef, all: List<Item>, onOpen: (Item) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var info by remember(ref.id) { mutableStateOf<PersonInfo?>(null) }
    var loaded by remember(ref.id) { mutableStateOf(false) }
    LaunchedEffect(ref.id) { info = CreditsRepo.person(ref.id); loaded = true }
    val owned by produceState(emptyList<Item>(), info, all) {
        val i = info ?: return@produceState
        value = withContext(Dispatchers.Default) {
            // Der Titel, von dem man kam, steht immer dabei (TMDB kennt bei manchen Personen nicht alle Arbeiten)
            val m = LibMatch.matches(all, i.titles, 80)
            val origin = ref.from?.let { id -> all.firstOrNull { it.id == id } }
            if (origin != null && m.none { it.id == origin.id }) listOf(origin) + m else m
        }
    }
    var expanded by remember(ref.id) { mutableStateOf(false) }
    var overflow by remember(ref.id) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(owned.isNotEmpty()) { if (owned.isNotEmpty()) { delay(150); runCatching { first.requestFocus() } } }

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 36.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    Box(Modifier.size(190.dp).clip(CircleShape).background(Surf)) {
                        val ph = info?.photo ?: ref.photo
                        if (ph != null) AsyncImage(ph, ref.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(raw = true, text = ref.name.take(1), color = Color.White, fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.align(Alignment.Center))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(raw = true, text = ref.name, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val i = info
                        val line = listOfNotNull(
                            i?.dept?.let { when (it) { "Directing" -> "Regie"; "Acting" -> "Schauspiel"; "Writing" -> "Drehbuch"; else -> it } },
                            i?.born?.let { "geboren $it" }, i?.place
                        ).joinToString("  ·  ")
                        if (line.isNotBlank()) Text(line, color = Muted, fontSize = 15.sp)
                        val bio = i?.bio
                        if (bio != null) {
                            Text(raw = true, text = bio, color = DS.TextSoft, fontSize = 15.sp, lineHeight = 22.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                                onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow })
                            if (overflow || expanded) SmallPill(if (expanded) "Weniger" else "Mehr lesen", trailing = if (expanded) AIcon.Up else AIcon.Down) { expanded = !expanded }
                        } else if (loaded && i == null) Text("Infos konnten nicht geladen werden.", color = Muted, fontSize = 15.sp)
                    }
                }
            }
            item {
                val i = info
                if (i != null) {
                    Text(
                        if (owned.isEmpty()) "Keine Titel in deiner Playlist" else "In deiner Playlist  ·  ${owned.size}",
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 34.dp)
                    )
                    // Seitlicher Innenabstand: das vergrößerte erste Cover wird links nicht mehr abgeschnitten
                    if (owned.isNotEmpty()) LazyRow(
                        Modifier.offset(x = (-14).dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(owned.size) { n ->
                            val it = owned[n]
                            PosterCard(it, { onOpen(it) }, Modifier.width(150.dp).then(if (n == 0) Modifier.focusRequester(first) else Modifier))
                        }
                    }
                    val rest = (i.titles.size - owned.size).coerceAtLeast(0)
                    if (rest > 0) Text("$rest weitere Titel dieser Person sind nicht in deiner Playlist.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                } else if (!loaded) Text("Lade …", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 34.dp))
            }
        }
    }
}
