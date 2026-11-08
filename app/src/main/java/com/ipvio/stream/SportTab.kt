package com.ipvio.stream

import android.widget.Toast

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.window.Dialog
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
import androidx.compose.ui.graphics.Brush
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
    var selectedStandingsLeague by remember { mutableStateOf("NFL") }
    var standingsRefresh by remember { mutableIntStateOf(0) }
    val standings by produceState<SportLogic.StandingsTable?>(null, selectedStandingsLeague, standingsRefresh) {
        value = withContext(IO) { SportLogic.fetchStandings(selectedStandingsLeague) }
    }
    var liveOnlyScores by remember { mutableStateOf(false) }
    var channelOnlyScores by remember { mutableStateOf(false) }
    var hubSection by remember { mutableStateOf("Scores") }
    var channelPickerGame by remember { mutableStateOf<SportLogic.ScoreGame?>(null) }
    var selectedPreviewGameId by remember { mutableStateOf<String?>(null) }
    val favoriteTeams = remember(context) { mutableStateOf(context.getSharedPreferences("sports_hub", 0).getStringSet("favorite_teams", emptySet())?.toSet() ?: emptySet()) }
    fun toggleFavorite(team: String) {
        val next = favoriteTeams.value.toMutableSet().apply { if (!add(team)) remove(team) }
        favoriteTeams.value = next
        context.getSharedPreferences("sports_hub", 0).edit().putStringSet("favorite_teams", next).apply()
    }
    val visibleScoreGames = scoreGames.filter { game ->
        (selectedScoreLeague == "All" || game.league == selectedScoreLeague) &&
            (!liveOnlyScores || game.state.equals("in", true)) &&
            (!channelOnlyScores || channels.any { SportLogic.scoreChannelRank(game, it) >= 5 })
    }
    fun channelsForGame(game: SportLogic.ScoreGame): List<Item> {
        // Sport/league/team matches first; FOX One and ESPN are secondary fallbacks.
        val sportMatches = live.map { it to SportLogic.scoreChannelRank(game, it) }
            .filter { it.second >= 5 }.sortedByDescending { it.second }.map { it.first }
        val generalFallbacks = liveChannelsForSports(live).filter { channel ->
            channel.title.contains("FOX One", true) || channel.group.contains("FOX One", true) ||
                channel.title.contains("ESPN", true) || channel.group.contains("ESPN", true)
        }
        return (sportMatches + generalFallbacks).distinctBy { it.id }
    }
    fun playScoreGame(game: SportLogic.ScoreGame) {
        val choices = channelsForGame(game)
        val channel = choices.firstOrNull()
        if (channel == null) {
            Toast.makeText(context, "No matching sports channel found for " + game.league + ".", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, "Opening " + channel.title + " — hold the game for channel choices.", Toast.LENGTH_LONG).show()
        onPlay(channel, choices)
    }
    fun chooseScoreChannel(game: SportLogic.ScoreGame) {
        if (channelsForGame(game).isEmpty()) Toast.makeText(context, "No matching sports channels found.", Toast.LENGTH_LONG).show()
        else channelPickerGame = game
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
                Column(verticalArrangement = Arrangement.spacedBy(dpx(16))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(22))) {
                        listOf("Scores", "My teams", "Standings", "Multi-View").forEach { label ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(6))) {
                                Text(label, color = if (hubSection == label) Color.White else Muted, fontSize = spx(18), fontWeight = if (hubSection == label) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.clickable { hubSection = label })
                                Box(Modifier.width(if (hubSection == label) dpx(64) else 0.dp).height(dpx(3)).clip(RoundedCornerShape(50)).background(if (hubSection == label) Accent1 else Color.Transparent))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("TODAY", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(DS.Overlay2).clickable { selectedScoreLeague = "All" }.padding(horizontal = dpx(14), vertical = dpx(9)))
                        Text("↻", color = Color.White, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(DS.Overlay2).clickable { tick++ }.padding(horizontal = dpx(12), vertical = dpx(6)))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                        Text("SPORTS HUB", color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        Text("${channels.size} sports channels", color = Muted, fontSize = 13.sp)
                        if (slots == null) Text("• Updating", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            if (hubSection == "Scores") item(key = "featured-score") {
                val featured = visibleScoreGames.firstOrNull { it.id == selectedPreviewGameId }
                    ?: visibleScoreGames.firstOrNull { it.state.equals("in", true) }
                    ?: visibleScoreGames.firstOrNull()
                if (featured != null) {
                    val previewChannel = channelsForGame(featured).firstOrNull()
                    Row(Modifier.fillMaxWidth().height(dpx(238)), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                        Box(Modifier.weight(1.15f).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusM)).background(Color.Black)) {
                            if (previewChannel != null) {
                                SecondStream(previewChannel, split = false, audioOn = true, modifier = Modifier.fillMaxSize())
                            } else {
                                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF173E55), Color(0xFF101A35)))), contentAlignment = Alignment.Center) {
                                    Text("No matching stream found for this game", color = Color.White, fontSize = 14.sp)
                                }
                            }
                            Text(if (featured.state.equals("in", true)) "● LIVE PREVIEW" else "GAME PREVIEW",
                                color = PT.Live, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.align(Alignment.TopStart).padding(dpx(10)).clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x99000000)).padding(horizontal = dpx(9), vertical = dpx(5)))
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusM))
                            .background(Color(0xCC17243D)).border(1.dp, Color(0x337FA9D8), RoundedCornerShape(DS.RadiusM)).padding(dpx(18)),
                            verticalArrangement = Arrangement.SpaceEvenly) {
                            Text(featured.league.uppercase(), color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(featured.name, color = Color.White, fontSize = spx(18), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            ScoreTeamRow(featured.away, featured.awayLogo, featured.awayScore)
                            ScoreTeamRow(featured.home, featured.homeLogo, featured.homeScore)
                            Text(featured.detail.ifBlank { featured.status }, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(dpx(150)).clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x221FFFFFFF)), contentAlignment = Alignment.Center) {
                        Text(if (scoreGames.isEmpty()) "Loading games…" else "No games listed for this league", color = Muted, fontSize = 14.sp)
                    }
                }
            }
            if (hubSection == "Scores") item(key = "score-filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(9)), contentPadding = PaddingValues(vertical = dpx(4))) {
                    item { FilterPill("With a channel", channelOnlyScores) { channelOnlyScores = !channelOnlyScores } }
                    item { FilterPill("● Live", liveOnlyScores) { liveOnlyScores = !liveOnlyScores } }
                    item { FilterPill("All sports", selectedScoreLeague == "All") { selectedScoreLeague = "All"; selectedPreviewGameId = null } }
                    items(scoreLeagues, key = { "league-chip-" + it }) { league ->
                        FilterPill(league, selectedScoreLeague == league) { selectedScoreLeague = league; selectedPreviewGameId = null }
                    }
                }
            }
            if (hubSection == "Scores") item(key = "score-section") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                        Text(if (selectedScoreLeague == "All") "Live scores & games" else selectedScoreLeague, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                        Text("${visibleScoreGames.size} games", color = Muted, fontSize = 13.sp)
                    }
                    if (visibleScoreGames.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(12)), contentPadding = PaddingValues(vertical = dpx(4))) {
                            items(visibleScoreGames, key = { it.league + "|" + it.id }) { game -> ScoreGameCard(game, onClick = { selectedPreviewGameId = game.id }, onLongClick = { chooseScoreChannel(game) }) }
                        }
                    } else {
                        Text("Choose another sport or check back when games are scheduled.", color = Muted, fontSize = 14.sp)
                    }
                }
            }
            if (hubSection == "Scores" && slots != null && (allNow.isNotEmpty() || allSoon.isNotEmpty())) item(key = "filters") {
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
            if (hubSection == "Scores" && now.isNotEmpty()) item(key = "now") {
                SportRow("Jetzt live", now, first = true) { s -> onPlay(s.ch, channels.ifEmpty { now.map { it.ch } }) }
            }
            if (hubSection == "Scores" && soon.isNotEmpty()) item(key = "soon") {
                SportRow(when (win) { 0 -> "Gleich"; 1 -> "Heute"; else -> "Morgen" }, soon, first = now.isEmpty()) { s -> onPlay(s.ch, channels.ifEmpty { soon.map { it.ch } }) }
            }
            if (hubSection == "Scores" && chShown.isNotEmpty()) item(key = "chs") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Text("Sportsender", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(14)), contentPadding = PaddingValues(vertical = dpx(8), horizontal = dpx(4))) {
                        itemsIndexed(chShown, key = { i, c -> c.id + "#" + i }) { i, c ->
                            SportChannelTile(c, if (i == 0 && now.isEmpty() && soon.isEmpty()) Modifier.focusRequester(ContentFocus.req) else Modifier) { onPlay(c, chShown) }
                        }
                    }
                }
            }
            if (hubSection == "My teams") item(key = "my-teams") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(16))) {
                    Text("My teams · Follow your favorites", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                    val teams = scoreGames.flatMap { listOf(it.home, it.away) }.distinct().sorted()
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                        items(teams, key = { "team-" + it }) { team ->
                            FilterPill((if (favoriteTeams.value.contains(team)) "★ " else "☆ ") + team, favoriteTeams.value.contains(team)) { toggleFavorite(team) }
                        }
                    }
                    val followed = scoreGames.filter { favoriteTeams.value.contains(it.home) || favoriteTeams.value.contains(it.away) }
                    if (followed.isEmpty()) Text("Choose teams above to follow them. Their games will appear here.", color = Muted)
                    else LazyColumn(verticalArrangement = Arrangement.spacedBy(dpx(10)), modifier = Modifier.height(dpx(360))) {
                        items(followed, key = { "followed-" + it.id }) { game -> ScoreGameCard(game, onClick = { playScoreGame(game) }, onLongClick = { chooseScoreChannel(game) }) }
                    }
                }
            }
            if (hubSection == "Standings") item(key = "standings") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(14))) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                        Text("Standings", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("↻ Refresh", color = Accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2)
                                .clickable { standingsRefresh++ }.padding(horizontal = dpx(12), vertical = dpx(8)))
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(8)), contentPadding = PaddingValues(vertical = dpx(4))) {
                        items(scoreLeagues, key = { "standings-" + it }) { league ->
                            FilterPill(league, selectedStandingsLeague == league) { selectedStandingsLeague = league }
                        }
                    }
                    Text(selectedStandingsLeague, color = Accent2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    val table = standings
                    if (table == null) {
                        Text("Loading ${selectedStandingsLeague} standings…", color = Muted)
                    } else if (table.rows.isEmpty()) {
                        Text("Standings are not currently available for ${selectedStandingsLeague}. Try refreshing later.", color = Muted)
                    } else {
                        table.groups.forEach { group ->
                            val groupRows = table.rows.filter { it.group == group }
                            if (groupRows.isNotEmpty()) {
                                Text(group, color = Color.White, fontSize = spx(18), fontWeight = FontWeight.Bold)
                                groupRows.forEach { row ->
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
                                        .background(DS.Overlay2).padding(horizontal = dpx(12), vertical = dpx(10)),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                                        Text(row.rank, color = Accent2, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(30)))
                                        if (row.logo.isNotBlank()) AsyncImage(row.logo, null, Modifier.size(dpx(30)), contentScale = ContentScale.Fit)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(3))) {
                                            Text(row.team, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (row.summary.isNotBlank()) Text(row.summary, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text(row.record, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        val ungrouped = table.rows.filter { it.group.isBlank() }
                        if (ungrouped.isNotEmpty()) ungrouped.forEach { row ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2)
                                .padding(horizontal = dpx(12), vertical = dpx(10)), verticalAlignment = Alignment.CenterVertically) {
                                Text(row.rank, color = Accent2, fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(30)))
                                if (row.logo.isNotBlank()) AsyncImage(row.logo, null, Modifier.size(dpx(30)), contentScale = ContentScale.Fit)
                                Text(row.team, color = Color.White, modifier = Modifier.weight(1f))
                                Text(row.record, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (hubSection == "Multi-View") item(key = "multi-view") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Text("Quick view", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                    Text("Choose a league to collect its games, then open a channel for each matchup. Simultaneous multi-screen playback depends on device support and is not enabled yet.", color = Muted)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(8))) { items(scoreLeagues, key = { "quick-" + it }) { league -> FilterPill(league, selectedScoreLeague == league) { selectedScoreLeague = league } } }
                    val leagueGames = scoreGames.filter { selectedScoreLeague == "All" || it.league == selectedScoreLeague }
                    if (leagueGames.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(10))) { items(leagueGames, key = { "quick-game-" + it.id }) { game -> ScoreGameCard(game, onClick = { playScoreGame(game) }, onLongClick = { chooseScoreChannel(game) }) } }
                }
            }
            if (hubSection == "Scores" && nothing && slots != null && (allNow.isNotEmpty() || allSoon.isNotEmpty())) item(key = "none") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Text("Keine Sendungen für diese Auswahl", color = Muted, fontSize = 18.sp)
                    FilterPill("Filter zurücksetzen", false) { sport = -1; win = 0 }
                }
            }
        }
        channelPickerGame?.let { game ->
            val choices = channelsForGame(game)
            Dialog(onDismissRequest = { channelPickerGame = null }) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusL))
                        .background(DS.Overlay2).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(DS.RadiusL))
                        .padding(dpx(18)),
                    verticalArrangement = Arrangement.spacedBy(dpx(12))
                ) {
                    Text("Choose channel · ${game.away} vs ${game.home}", color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold)
                    if (choices.isEmpty()) {
                        Text("No sports channels or FOX One / ESPN channels were found in your playlist.", color = Muted)
                    } else {
                        LazyColumn(modifier = Modifier.height(dpx(360)), verticalArrangement = Arrangement.spacedBy(dpx(6))) {
                            items(choices, key = { "pick-" + it.id }) { channel ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
                                        .background(Color(0x18FFFFFF)).clickable {
                                            channelPickerGame = null
                                            onPlay(channel, choices)
                                        }.padding(dpx(12)),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(dpx(10))
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(3))) {
                                        Text(channel.title, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(if (SportLogic.isSportChannel(channel)) "Sports category match" else "General sports network", color = Muted, fontSize = 11.sp)
                                    }
                                    Text("WATCH", color = Accent2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Text("CANCEL", color = Accent2, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.End).clickable { channelPickerGame = null }.padding(8.dp))
                }
            }
        }
    }
}

private fun liveChannelsForSports(live: List<Item>): List<Item> =
    live.filter { Regex("fox\\s?one|espn\\s?\\+?|sports?\\s?networks?", RegexOption.IGNORE_CASE).containsMatchIn(it.title + " " + it.group) }

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
@OptIn(ExperimentalFoundationApi::class)
private fun ScoreGameCard(game: SportLogic.ScoreGame, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        Modifier.width(dpx(300)).clip(shape)
            .background(Color(0x1AFFFFFF))
            .border(1.dp, Color(0x1AFFFFFF), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
