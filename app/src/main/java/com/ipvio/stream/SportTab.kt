package com.ipvio.stream

import android.widget.Toast

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Sport (Runde 20): was jetzt und gleich im Sport läuft, dazu alle Sportsender. Alles aus der eigenen Playlist und
 * ihrem TV-Programm – ohne Ergebnis-Dienste von außen. OK auf einer Sendung oder einem Sender startet ihn.
 */
@Composable
fun SportTab(live: List<Item>, onPlay: (Item, List<Item>) -> Unit) {
    SideEffect { ContentFocus.tab = MenuLogic.SPORT }
    val channels = remember(live) { live.filter { SportLogic.isSportChannel(it) } }
    val context = LocalContext.current
    // Programm neu einordnen: beim Öffnen, wenn der TV-Guide fertig ist, und jede Minute
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val slots by produceState<Pair<List<SportLogic.Slot>, List<SportLogic.Slot>>?>(null, live, tick, Xmltv.channelCount) {
        value = withContext(Dispatchers.Default) {
            SportLogic.slots(live, { Xmltv.forChannel(it.tvgId) }, System.currentTimeMillis() / 1000, 48 * 3600L, 600)
        }
    }
    val allNow = slots?.first.orEmpty()
    val allSoon = slots?.second.orEmpty()
    val scoreGames by produceState<List<SportLogic.ScoreGame>>(emptyList(), tick) {
        value = withContext(IO) { SportLogic.fetchScoreboards() }
    }
    val scoreLeagues = listOf("NFL", "College Football", "NBA", "WNBA", "Men's College Basketball", "MLB", "NHL", "MLS", "Premier League", "Champions League", "UFC", "Tennis", "Golf", "Formula 1", "Cricket")
    var selectedScoreLeague by remember { mutableStateOf("All") }
    val visibleScoreGames = if (selectedScoreLeague == "All") scoreGames else scoreGames.filter { it.league == selectedScoreLeague }
    fun playScoreGame(game: SportLogic.ScoreGame) {
        val ranked = channels.map { it to SportLogic.scoreChannelRank(game, it) }
            .filter { it.second >= 5 }
            .sortedByDescending { it.second }
        val matches = ranked.map { it.first }
        val channel = matches.firstOrNull()
        if (channel == null) {
            Toast.makeText(context, "No clearly matching " + game.league + " channel was found. Choose a sports channel from your playlist instead.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Opening " + channel.title + ". Check that your provider carries this game.", Toast.LENGTH_LONG).show()
            onPlay(channel, matches)
        }
    }
    // Runde 22: Filter nach Zeitfenster (3 Std. / heute / morgen) und Sportart; die Chips zeigen nur Sportarten mit Treffern
    var win by remember { mutableIntStateOf(0) }
    var sport by remember { mutableIntStateOf(-1) }
    val zone = General.zone()
    val nowSec = remember(tick, slots) { System.currentTimeMillis() / 1000 }
    val nowCats = remember(allNow) { allNow.map { SportLogic.categoryOf(it.prog.title, it.ch.title + " " + it.ch.group) } }
    val soonCats = remember(allSoon) { allSoon.map { SportLogic.categoryOf(it.prog.title, it.ch.title + " " + it.ch.group) } }
    val soonIn = remember(allSoon, win, nowSec) { allSoon.indices.filter { SportLogic.startsIn(win, allSoon[it].prog.start, nowSec, zone) } }
    val showNow = win != 2
    val counts = IntArray(SportLogic.CATS.size + 1)
    if (showNow) nowCats.forEach { counts[it]++ }
    soonIn.forEach { counts[soonCats[it]]++ }
    val chipCats = counts.indices.filter { counts[it] > 0 || it == sport }.sortedByDescending { counts[it] }
    val now = if (!showNow) emptyList() else allNow.filterIndexed { i, _ -> sport < 0 || nowCats[i] == sport }
    val soon = soonIn.filter { sport < 0 || soonCats[it] == sport }.map { allSoon[it] }
    val chCats = remember(channels) { channels.map { SportLogic.categoryOf("", it.title + " " + it.group) } }
    val chShown = if (sport < 0) channels else channels.filterIndexed { i, _ -> chCats[i] == sport }
    val nothing = now.isEmpty() && soon.isEmpty() && chShown.isEmpty()
    Box(Modifier.fillMaxSize()) {
        if (channels.isEmpty() && allNow.isEmpty() && allSoon.isEmpty() && slots != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(AIcon.Live, "Keine Sportsender gefunden", "Sportsender werden an Kategorie und Namen erkannt (z. B. „Sport“, „DAZN“, „Eurosport“). Sport-Sendungen erscheinen, sobald der TV-Guide geladen ist.")
            }
            return@Box
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(start = dpx(40), end = dpx(40)),
            contentPadding = PaddingValues(top = dpx(30), bottom = dpx(60)),
            verticalArrangement = Arrangement.spacedBy(dpx(26))
        ) {
            item(key = "head") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Sport", color = Color.White, fontSize = spx(34), fontWeight = FontWeight.ExtraBold)
                    Text("${channels.size} Sender", color = Muted, fontSize = 16.sp)
                    if (slots == null) Text("Lade …", color = Muted, fontSize = 16.sp)
                    else if (Xmltv.channelCount == 0) Text("TV-Guide wird noch geladen", color = Muted, fontSize = 15.sp)
                }
            }
            item(key = "scores") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Live scores & games", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                        Text("ESPN", color = Muted, fontSize = 12.sp)
                    }
                    Text("Choose a sport", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(8)), contentPadding = PaddingValues(vertical = dpx(4), horizontal = dpx(4))) {
                        item(key = "all-score-leagues") { FilterPill("All sports", selectedScoreLeague == "All") { selectedScoreLeague = "All" } }
                        items(scoreLeagues, key = { "score-league-" + it }) { league ->
                            FilterPill(league, selectedScoreLeague == league) { selectedScoreLeague = league }
                        }
                    }
                    if (visibleScoreGames.isEmpty()) {
                        Text(if (scoreGames.isEmpty()) "Scoreboards are temporarily unavailable. Select a league to browse." else "No games currently listed for " + selectedScoreLeague + ".", color = Muted, fontSize = 14.sp)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(12)), contentPadding = PaddingValues(vertical = dpx(6), horizontal = dpx(4))) {
                            items(visibleScoreGames, key = { it.league + "|" + it.id }) { game ->
                                ScoreGameCard(game) { playScoreGame(game) }
                            }
                        }
                    }
                }
            }
            if (slots != null && (allNow.isNotEmpty() || allSoon.isNotEmpty())) item(key = "filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(10)), contentPadding = PaddingValues(vertical = dpx(4), horizontal = dpx(4))) {
                    val wins = listOf("Nächste 3 Std.", "Heute", "Morgen")
                    itemsIndexed(wins, key = { i, _ -> "w$i" }) { i, l ->
                        FilterPill(l, win == i, if (i == 0 && nothing) Modifier.focusRequester(ContentFocus.req) else Modifier) { win = i }
                    }
                    item(key = "sep") { Box(Modifier.width(2.dp).height(dpx(28)).background(DS.Overlay3)) }
                    item(key = "all") { FilterPill("Alle · " + counts.sum(), sport < 0) { sport = -1 } }
                    itemsIndexed(chipCats, key = { _, c -> "c$c" }) { _, c ->
                        FilterPill(SportLogic.catName(c) + " · " + counts[c], sport == c) { sport = c }
                    }
                }
            }
            if (now.isNotEmpty()) item(key = "now") {
                SportRow("Jetzt live", now, first = true) { s -> onPlay(s.ch, channels.ifEmpty { now.map { it.ch } }) }
            }
            if (soon.isNotEmpty()) item(key = "soon") {
                SportRow(when (win) { 0 -> "Gleich"; 1 -> "Heute"; else -> "Morgen" }, soon, first = now.isEmpty()) { s -> onPlay(s.ch, channels.ifEmpty { soon.map { it.ch } }) }
            }
            if (chShown.isNotEmpty()) item(key = "chs") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Text("Sportsender", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(4))) {
                        itemsIndexed(chShown, key = { i, c -> c.id + "#" + i }) { i, c ->
                            SportChannelTile(c, if (i == 0 && now.isEmpty() && soon.isEmpty()) Modifier.focusRequester(ContentFocus.req) else Modifier) { onPlay(c, chShown) }
                        }
                    }
                }
            }
            if (nothing && slots != null && (allNow.isNotEmpty() || allSoon.isNotEmpty())) item(key = "none") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Text("Keine Sendungen für diese Auswahl", color = Muted, fontSize = 18.sp)
                    FilterPill("Filter zurücksetzen", false) { sport = -1; win = 0 }
                }
            }
        }
    }
}

@Composable
private fun SportRow(title: String, list: List<SportLogic.Slot>, first: Boolean, onClick: (SportLogic.Slot) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
        Text(title, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(4))) {
            // Derselbe Sender kann mehrfach vorkommen (mehrere Playlists, doppelte Einträge): Index im Schlüssel verhindert den Absturz
            itemsIndexed(list, key = { i, s -> s.ch.id + "|" + s.prog.start + "#" + i }) { i, s ->
                SportCard(s, if (first && i == 0) Modifier.focusRequester(ContentFocus.req) else Modifier) { onClick(s) }
            }
        }
    }
}

@Composable
private fun SportCard(s: SportLogic.Slot, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.05f) else 1f, label = "sport")
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        modifier.width(dpx(330)).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
            .background(if (f) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .border(if (f) 3.dp else 1.dp, if (f) Accent1 else Color(0x1AFFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick)
            .padding(dpx(14)),
        verticalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
            Box(Modifier.size(dpx(44)).clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
                if (s.ch.logo != null) AsyncImage(s.ch.logo, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
                else Text(raw = true, text = s.ch.title.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Text(raw = true, text = s.ch.title, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (s.live) Box(Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(PT.Live).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Text(raw = true, text = s.prog.title, color = Color.White, fontSize = spx(18), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(raw = true, text = hhmm(s.prog.start) + "–" + hhmm(s.prog.end), color = Accent2, fontSize = 14.sp)
        if (s.live) {
            val frac = ((System.currentTimeMillis() / 1000 - s.prog.start).toFloat() / (s.prog.end - s.prog.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(DS.Overlay3)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent2))
            }
        } else Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun SportChannelTile(c: Item, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "sportch")
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        modifier.width(dpx(170)).graphicsLayer { scaleX = sc; scaleY = sc }.clip(shape)
            .background(if (f) Color(0x33FFFFFF) else Color(0x14FFFFFF))
            .border(if (f) 3.dp else 1.dp, if (f) Accent1 else Color(0x1AFFFFFF), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick)
            .padding(dpx(12)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        Box(Modifier.size(dpx(96), dpx(64)), contentAlignment = Alignment.Center) {
            if (c.logo != null) AsyncImage(c.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(raw = true, text = c.title.take(3).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(raw = true, text = c.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}


@Composable
private fun ScoreGameCard(game: SportLogic.ScoreGame, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        Modifier.width(dpx(300)).clip(shape)
            .background(Color(0x1AFFFFFF))
            .border(1.dp, Color(0x1AFFFFFF), shape)
            .clickable(onClick = onClick)
            .padding(dpx(14)),
        verticalArrangement = Arrangement.spacedBy(dpx(10))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(game.league, color = Accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                when (game.state.lowercase()) {
                    "in" -> "LIVE"
                    "post" -> "FINAL"
                    else -> game.detail
                },
                color = if (game.state.equals("in", true)) PT.Live else Muted,
                fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1
            )
        }
        ScoreTeamRow(game.away, game.awayLogo, game.awayScore)
        ScoreTeamRow(game.home, game.homeLogo, game.homeScore)
        Text(game.status, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ScoreTeamRow(name: String, logo: String, score: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(dpx(38)).clip(RoundedCornerShape(DS.RadiusXs)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
            if (logo.isNotBlank()) AsyncImage(logo, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
            else Text(name.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(score, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}
