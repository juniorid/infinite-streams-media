package com.ipvio.stream

import android.content.Intent

import android.widget.Toast
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.focus.FocusRequester
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
    // EPG scan is expensive on large IPTV lists: refresh every 5 minutes and only while Scores is visible.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(300_000); tick++ } }
    var hubSection by remember { mutableStateOf("Scores") }
    val sportsInput = remember(channels) { channels }
    val slots by produceState<Pair<List<SportLogic.Slot>, List<SportLogic.Slot>>?>(null, sportsInput, tick, Xmltv.channelCount) {
        value = withContext(Dispatchers.Default) {
            SportLogic.slots(sportsInput, { Xmltv.forChannel(it.tvgId) }, System.currentTimeMillis() / 1000, 48 * 3600L, 300)
        }
    }
    val allNow = slots?.first.orEmpty()
    val allSoon = slots?.second.orEmpty()
    val scoreLeagues = listOf("NFL", "College Football", "College Soccer (Men)", "College Soccer (Women)", "NBA", "WNBA", "Men's College Basketball", "MLB", "NHL", "MLS", "Premier League", "Champions League", "UFC", "Tennis", "Golf", "Formula 1", "Cricket")
    val enabledLeagues = remember(context) {
        mutableStateOf(context.getSharedPreferences("sports_hub", 0)
            .getStringSet("enabled_leagues", scoreLeagues.toMutableSet())?.toSet() ?: scoreLeagues.toSet())
    }
    var scoreRefresh by remember { mutableIntStateOf(0) }
    var manageFollows by remember { mutableStateOf(false) }
    // Keep fetched scoreboards cached while moving between Sports Hub tabs. Fetch only leagues
    // that have not been loaded yet; tab changes no longer trigger another network round-trip.
    var scoreGames by remember { mutableStateOf<List<SportLogic.ScoreGame>>(emptyList()) }
    var loadedScoreLeagues by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loadedSeasonLeagues by remember { mutableStateOf<Set<String>>(emptySet()) }
    val requestedScoreLeagues = if (hubSection == "My teams" || manageFollows) scoreLeagues.toSet() else enabledLeagues.value
    LaunchedEffect(scoreRefresh, requestedScoreLeagues, hubSection) {
        if (hubSection == "My teams") {
            val missingSeason = requestedScoreLeagues - loadedSeasonLeagues
            if (missingSeason.isNotEmpty()) {
                val fresh = withContext(IO) { SportLogic.fetchScoreboards(missingSeason, fullSeason = true) }
                scoreGames = (scoreGames.filterNot { it.league in missingSeason } + fresh).distinctBy { it.league + "|" + it.id }
                loadedSeasonLeagues = loadedSeasonLeagues + missingSeason
            }
        } else {
            val missing = requestedScoreLeagues - loadedScoreLeagues
            if (missing.isNotEmpty()) {
                val fresh = withContext(IO) { SportLogic.fetchScoreboards(missing) }
                scoreGames = (scoreGames.filterNot { it.league in missing } + fresh).distinctBy { it.league + "|" + it.id }
                loadedScoreLeagues = loadedScoreLeagues + missing
            }
        }
    }
    val activeScoreLeagues = scoreLeagues.filter { enabledLeagues.value.contains(it) }
    var manageLeagues by remember { mutableStateOf(false) }
    val editLeaguesFocus = remember { FocusRequester() }
    val manageFollowsFocus = remember { FocusRequester() }
    var manageFollowsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(hubSection) { if (hubSection == "My teams") { delay(250); runCatching { manageFollowsFocus.requestFocus() } } }
    var editLeagueFocused by remember { mutableStateOf(false) }
    var todayFocused by remember { mutableStateOf(false) }
    var refreshFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(150); runCatching { editLeaguesFocus.requestFocus() } }
    var selectedScoreLeague by remember { mutableStateOf("All") }
    var selectedStandingsLeague by remember { mutableStateOf("NFL") }
    var selectedStandingsConference by remember { mutableStateOf("All") }
    var standingsRefresh by remember { mutableIntStateOf(0) }
    var standings by remember { mutableStateOf<SportLogic.StandingsTable?>(null) }
    var selectedTeam by remember { mutableStateOf<String?>(null) }
    var standingsLoadedLeague by remember { mutableStateOf<String?>(null) }
    var standingsLoadedRefresh by remember { mutableIntStateOf(-1) }
    LaunchedEffect(selectedStandingsLeague, standingsRefresh, hubSection) {
        if (hubSection == "Standings" &&
            (standingsLoadedLeague != selectedStandingsLeague || standingsLoadedRefresh != standingsRefresh)) {
            standings = null
            selectedStandingsConference = "All"
            standings = withContext(IO) { SportLogic.fetchStandings(selectedStandingsLeague) }
            standingsLoadedLeague = selectedStandingsLeague
            standingsLoadedRefresh = standingsRefresh
        }
    }
    var liveOnlyScores by remember { mutableStateOf(false) }
    var channelOnlyScores by remember { mutableStateOf(false) }
    var channelPickerGame by remember { mutableStateOf<SportLogic.ScoreGame?>(null) }
    var gameCenterGame by remember { mutableStateOf<SportLogic.ScoreGame?>(null) }
    var selectedPreviewGameId by remember { mutableStateOf<String?>(null) }
    var selectedFollowLeague by remember { mutableStateOf("NFL") }
    var followTeams by remember { mutableStateOf<List<SportLogic.StandingRow>>(emptyList()) }
    var followTeamQuery by remember { mutableStateOf("") }
    var sportsSearchQuery by remember { mutableStateOf("") }
    val sportsVoiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { sportsSearchQuery = it }
    }
    LaunchedEffect(manageFollows, selectedFollowLeague) {
        if (manageFollows) {
            followTeams = withContext(IO) { SportLogic.fetchStandings(selectedFollowLeague).rows }
        }
    }
    var multiViewChannels by remember { mutableStateOf<List<Item>>(emptyList()) }
    var showMultiViewPicker by remember { mutableStateOf(false) }
    var showMultiViewFullscreen by remember { mutableStateOf(false) }
    var selectedMultiViewChannelId by remember { mutableStateOf<String?>(null) }
    var multiViewCategory by remember { mutableStateOf<String?>(null) }
    var multiViewCategoryMenu by remember { mutableStateOf(false) }
    val multiViewCategories = remember(live) { live.map { it.group.ifBlank { "Other" } }.distinct().sorted() }
    val favoriteTeams = remember(context) { mutableStateOf(context.getSharedPreferences("sports_hub", 0).getStringSet("favorite_teams", emptySet())?.toSet() ?: emptySet()) }
    fun toggleFavorite(team: String) {
        val next = favoriteTeams.value.toMutableSet().apply { if (!add(team)) remove(team) }
        favoriteTeams.value = next
        context.getSharedPreferences("sports_hub", 0).edit().putStringSet("favorite_teams", next).apply()
    }
    val visibleScoreGames = scoreGames.filter { game ->
        (if (selectedScoreLeague == "All") activeScoreLeagues.contains(game.league) else game.league == selectedScoreLeague) &&
            (!liveOnlyScores || game.state.equals("in", true)) &&
            (!channelOnlyScores || channels.any { SportLogic.scoreChannelRank(game, it) >= 5 }) &&
            (sportsSearchQuery.isBlank() || listOf(game.home, game.away, game.league).any { it.contains(sportsSearchQuery, true) })
    }
    fun channelsForGame(game: SportLogic.ScoreGame): List<Item> {
        // Sport/league/team matches first; FOX One and ESPN are secondary fallbacks.
        val sportMatches = live.map { it to SportLogic.scoreChannelRank(game, it) }
            .filter { it.second >= 5 }.sortedByDescending { it.second }.map { it.first }
        val generalFallbacks = liveChannelsForSports(live).filter { channel ->
            channel.title.contains("FOX One", true) || channel.group.contains("FOX One", true) ||
                channel.title.contains("ESPN", true) || channel.group.contains("ESPN", true)
        }
        // Keep radio/audio-only feeds available, but place them after video feeds.
        return (sportMatches + generalFallbacks).distinctBy { it.id }
            .sortedBy { channel ->
                Regex("\\b(radio|audio[- ]?only|commentary[- ]?only)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(channel.title + " " + channel.group)
            }
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
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(dpx(12)),
                        contentPadding = PaddingValues(horizontal = dpx(4), vertical = dpx(4))
                    ) {
                        items(listOf("Scores", "My teams", "Standings", "Multi-View"), key = { "hub-tab-" + it }) { label ->
                            var tabFocused by remember(label) { mutableStateOf(false) }
                            Column(
                                Modifier.clip(RoundedCornerShape(DS.RadiusS))
                                    .background(if (tabFocused || hubSection == label) Color(0xFF245B22) else Color(0xFF202A24))
                                    .border(if (tabFocused) 3.dp else 1.dp, if (tabFocused) Color(0xFF9BE15D) else Color(0x5576B947), RoundedCornerShape(DS.RadiusS))
                                    .onFocusChanged { tabFocused = it.isFocused }
                                    .clickable { hubSection = label; if (label == "Multi-View") showMultiViewFullscreen = true }
                                    .padding(horizontal = dpx(14), vertical = dpx(9)),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(dpx(4))
                            ) {
                                Text(label, color = Color.White, fontSize = spx(18), fontWeight = if (hubSection == label) FontWeight.Bold else FontWeight.Medium)
                                Box(Modifier.width(if (hubSection == label) dpx(54) else 0.dp).height(dpx(3)).clip(RoundedCornerShape(50)).background(if (hubSection == label) Color(0xFF9BE15D) else Color.Transparent))
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dpx(10))
                    ) {
                        Text("EDIT LEAGUES", color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.focusRequester(editLeaguesFocus).clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2)
                                .border(if (editLeagueFocused) 3.dp else 1.dp, if (editLeagueFocused) Color(0xFF9BE15D) else Color(0xFF557B48), RoundedCornerShape(DS.RadiusS))
                                .onFocusChanged { editLeagueFocused = it.isFocused }.clickable { manageLeagues = true }.padding(horizontal = dpx(14), vertical = dpx(10)))
                        Text("TODAY", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(DS.Overlay2)
                                .border(if (todayFocused) 3.dp else 1.dp, if (todayFocused) Color(0xFF9BE15D) else Color(0xFF557B48), RoundedCornerShape(50))
                                .onFocusChanged { todayFocused = it.isFocused }.clickable { selectedScoreLeague = "All" }.padding(horizontal = dpx(14), vertical = dpx(10)))
                        Text("↻", color = Color.White, fontSize = 20.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(DS.Overlay2)
                            .border(if (refreshFocused) 3.dp else 1.dp, if (refreshFocused) Color(0xFF9BE15D) else Color(0xFF557B48), RoundedCornerShape(50))
                            .onFocusChanged { refreshFocused = it.isFocused }.clickable { tick++; scoreGames = emptyList(); loadedScoreLeagues = emptySet(); loadedSeasonLeagues = emptySet(); scoreRefresh++ }.padding(horizontal = dpx(12), vertical = dpx(6)))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                        Text("SPORTS HUB", color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        Text("${channels.size} sports channels", color = Muted, fontSize = 13.sp)
                        if (slots == null) Text("• Updating", color = Muted, fontSize = 12.sp)
                    }
                    if (hubSection == "Scores" || hubSection == "My teams") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                            Field(sportsSearchQuery, { sportsSearchQuery = it }, "Search teams, leagues or games…", Modifier.weight(1f))
                            PillButton("Voice", icon = AIcon.Mic) {
                                runCatching {
                                    sportsVoiceLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a team, league or game"))
                                }.onFailure {
                                    Toast.makeText(context, "Speech recognition is not available on this device.", Toast.LENGTH_LONG).show()
                                }
                            }
                            PillButton("Clear") { sportsSearchQuery = "" }
                        }
                    }
                }
            }
            if (hubSection == "Scores") item(key = "featured-score") {
                val featured = visibleScoreGames.firstOrNull { it.id == selectedPreviewGameId }
                    ?: visibleScoreGames.firstOrNull { it.state.equals("in", true) }
                    ?: visibleScoreGames.firstOrNull()
                if (featured != null) FeaturedGamePanel(featured, channelsForGame(featured).firstOrNull(), onOpen = { playScoreGame(featured) }, onGameCenter = { gameCenterGame = featured })
                else Box(Modifier.fillMaxWidth().height(dpx(150)).clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x221FFFFFFF)), contentAlignment = Alignment.Center) {
                    Text(if (scoreGames.isEmpty()) "Loading games…" else "No games listed for this league", color = Muted, fontSize = 14.sp)
                }
            }
            if (hubSection == "Scores") item(key = "score-filters") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(9)), contentPadding = PaddingValues(vertical = dpx(4))) {
                    item { FilterPill("With a channel", channelOnlyScores) { channelOnlyScores = !channelOnlyScores } }
                    item { FilterPill("● Live", liveOnlyScores) { liveOnlyScores = !liveOnlyScores } }
                    item { FilterPill("All sports", selectedScoreLeague == "All") { selectedScoreLeague = "All"; selectedPreviewGameId = null } }
                    items(activeScoreLeagues, key = { "league-chip-" + it }) { league ->
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
                            items(visibleScoreGames, key = { it.league + "|" + it.id }) { game -> ScoreGameCard(game, onClick = { playScoreGame(game) }, onGameCenter = { gameCenterGame = game }, onLongClick = { chooseScoreChannel(game) }) }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("My Teams", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("MANAGE FOLLOWS", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.focusRequester(manageFollowsFocus)
                                .clip(RoundedCornerShape(DS.RadiusS)).background(if (manageFollowsFocused) Color(0xFF245B22) else DS.Overlay2)
                                .border(if (manageFollowsFocused) 3.dp else 1.dp, if (manageFollowsFocused) Color(0xFF9BE15D) else Color(0x5576B947), RoundedCornerShape(DS.RadiusS))
                                .onFocusChanged { manageFollowsFocused = it.isFocused }
                                .clickable { manageFollows = true }.padding(horizontal = dpx(14), vertical = dpx(10)))
                    }
                    val followed = scoreGames.filter { favoriteTeams.value.contains(it.home) || favoriteTeams.value.contains(it.away) }
                    val teamCards = remember(scoreGames, favoriteTeams.value) {
                        scoreGames.flatMap { game -> listOf(
                            Triple(game.home, game.homeLogo, game.league),
                            Triple(game.away, game.awayLogo, game.league)
                        ) }.filter { favoriteTeams.value.contains(it.first) }.distinctBy { it.first }
                    }
                    if (teamCards.isEmpty()) {
                        Text("No teams followed yet. Select Manage follows to choose a league and team.", color = Muted)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                            items(teamCards, key = { "team-logo-" + it.first }) { team ->
                                var focused by remember(team.first) { mutableStateOf(false) }
                                Column(Modifier.width(dpx(112)).clip(RoundedCornerShape(DS.RadiusM))
                                    .background(if (focused || selectedTeam == team.first) Color(0xFF245B22) else DS.Overlay2)
                                    .border(if (focused || selectedTeam == team.first) 3.dp else 1.dp,
                                        if (focused || selectedTeam == team.first) Color(0xFF9BE15D) else Color(0x3376B947), RoundedCornerShape(DS.RadiusM))
                                    .onFocusChanged { focused = it.isFocused }
                                    .clickable { selectedTeam = team.first }.padding(dpx(10)),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(6))) {
                                    if (team.second.isNotBlank()) AsyncImage(team.second, null, Modifier.size(dpx(58)), contentScale = ContentScale.Fit)
                                    else Box(Modifier.size(dpx(58)), contentAlignment = Alignment.Center) { Text(team.first.take(3), color = Color.White, fontWeight = FontWeight.Bold) }
                                    Text(team.first, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        val activeTeam = selectedTeam ?: teamCards.first().first
                        val teamGames = scoreGames.filter { it.home == activeTeam || it.away == activeTeam }.sortedBy { it.date }
                        val logo = teamCards.firstOrNull { it.first == activeTeam }?.second.orEmpty()
                        val isFinal: (SportLogic.ScoreGame) -> Boolean = { it.state.equals("post", true) || it.status.contains("final", true) }
                        val recent = teamGames.filter(isFinal).sortedByDescending { it.date }
                        val upcoming = teamGames.filterNot(isFinal).sortedBy { it.date }
                        val wins = recent.count { game ->
                            val homeScore = game.homeScore.toIntOrNull() ?: 0
                            val awayScore = game.awayScore.toIntOrNull() ?: 0
                            (game.home == activeTeam && homeScore > awayScore) ||
                                (game.away == activeTeam && awayScore > homeScore)
                        }
                        val losses = recent.size - wins
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(Color(0xCC17243D)).padding(dpx(16)),
                            verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                                if (logo.isNotBlank()) AsyncImage(logo, null, Modifier.size(dpx(86)), contentScale = ContentScale.Fit)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(3))) {
                                    Text(activeTeam, color = Color.White, fontSize = spx(25), fontWeight = FontWeight.ExtraBold)
                                    Text(teamGames.firstOrNull()?.league.orEmpty() + " · " + wins + "-" + losses + " · Full season", color = Muted, fontSize = 14.sp)
                                    Text("Recent form", color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(dpx(5))) {
                                        recent.take(5).forEach { game ->
                                            val won = if (game.home == activeTeam) (game.homeScore.toIntOrNull() ?: 0) > (game.awayScore.toIntOrNull() ?: 0)
                                                else (game.awayScore.toIntOrNull() ?: 0) > (game.homeScore.toIntOrNull() ?: 0)
                                            Box(Modifier.size(dpx(27)).clip(RoundedCornerShape(50)).background(if (won) Color(0xFF00A6D6) else Color(0xFFE35C76)), contentAlignment = Alignment.Center) {
                                                Text(if (won) "W" else "L", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                            Text("Upcoming", color = Accent2, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            if (upcoming.isEmpty()) Text("No upcoming games are currently listed.", color = Muted)
                            upcoming.take(6).forEach { game ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2)
                                    .clickable { playScoreGame(game) }.padding(dpx(10)), verticalAlignment = Alignment.CenterVertically) {
                                    val opponent = if (game.home == activeTeam) game.away else game.home
                                    val oppLogo = if (game.home == activeTeam) game.awayLogo else game.homeLogo
                                    if (oppLogo.isNotBlank()) AsyncImage(oppLogo, null, Modifier.size(dpx(34)), contentScale = ContentScale.Fit)
                                    Text((if (game.home == activeTeam) "vs " else "at ") + opponent, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(game.status.ifBlank { game.detail }, color = Muted, fontSize = 12.sp)
                                }
                            }
                            Text("Results", color = Accent2, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            recent.take(8).forEach { game ->
                                val opponent = if (game.home == activeTeam) game.away else game.home
                                val won = if (game.home == activeTeam) (game.homeScore.toIntOrNull() ?: 0) > (game.awayScore.toIntOrNull() ?: 0)
                                    else (game.awayScore.toIntOrNull() ?: 0) > (game.homeScore.toIntOrNull() ?: 0)
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2)
                                    .clickable { playScoreGame(game) }.padding(dpx(10)), verticalAlignment = Alignment.CenterVertically) {
                                    Text(opponent, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(game.awayScore + " - " + game.homeScore, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text(if (won) "W" else "L", color = if (won) Color(0xFF00B9E8) else Color(0xFFE35C76), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = dpx(12)))
                                }
                            }
                        }
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
                        items(activeScoreLeagues, key = { "standings-" + it }) { league ->
                            FilterPill(league, selectedStandingsLeague == league) {
                                selectedStandingsLeague = league
                                selectedStandingsConference = "All"
                            }
                        }
                    }
                    val table = standings
                    if (table == null) {
                        Text("Loading ${selectedStandingsLeague} standings…", color = Muted)
                    } else if (table.rows.isEmpty()) {
                        Text("Standings are not currently available for ${selectedStandingsLeague}. Try refreshing later.", color = Muted)
                    } else {
                        val conferenceOptions = table.rows.map { it.conference }.filter { it.isNotBlank() }.distinct()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (selectedStandingsLeague.contains("College", true)) "Conference" else "Conference", color = Accent2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(8)), contentPadding = PaddingValues(vertical = dpx(4))) {
                            item(key = "standing-conf-all") { FilterPill("All", selectedStandingsConference == "All") { selectedStandingsConference = "All" } }
                            items(conferenceOptions, key = { "standing-conf-" + it }) { conference ->
                                FilterPill(conference, selectedStandingsConference == conference) { selectedStandingsConference = conference }
                            }
                        }
                        val filteredRows = table.rows.filter { selectedStandingsConference == "All" || it.conference == selectedStandingsConference }
                        LazyColumn(Modifier.fillMaxWidth().height(dpx(520)), verticalArrangement = Arrangement.spacedBy(dpx(5))) {
                            item(key = "standing-columns") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dpx(5)), verticalAlignment = Alignment.CenterVertically) {
                                    listOf("TEAM", "W", "L", "T", "PCT", "PF", "PA", "DIFF", "STRK").forEachIndexed { i, label ->
                                        Text(label, color = Accent2, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.weight(if (i == 0) 2.8f else .62f), maxLines = 1)
                                    }
                                }
                            }
                            table.groups.distinct().forEach { group ->
                                val groupRows = filteredRows.filter { it.group == group }
                                if (groupRows.isNotEmpty()) {
                                    item(key = "standing-group-" + group) {
                                        Text(group, color = Color.White, fontSize = spx(18), fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(top = dpx(7)))
                                    }
                                    items(groupRows, key = { "standing-row-" + it.team }) { row ->
                                        var rowFocused by remember(row.team) { mutableStateOf(false) }
                                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
                                            .background(if (rowFocused) Color(0xFF245B22) else DS.Overlay2)
                                            .border(if (rowFocused) 2.dp else 1.dp, if (rowFocused) Color(0xFF9BE15D) else Color.Transparent, RoundedCornerShape(DS.RadiusS))
                                            .onFocusChanged { rowFocused = it.isFocused }
                                            .clickable { selectedStandingsConference = row.conference.ifBlank { "All" } }
                                            .padding(horizontal = dpx(9), vertical = dpx(9)),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(5))) {
                                            Text(row.rank, color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(22)))
                                            if (row.logo.isNotBlank()) AsyncImage(row.logo, null, Modifier.size(dpx(30)), contentScale = ContentScale.Fit)
                                            Text(row.team, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(2.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            listOf(row.wins, row.losses, row.ties, row.pct, row.pointsFor, row.pointsAgainst, row.difference, row.streak).forEach { value ->
                                                Text(value.ifBlank { "—" }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.weight(.62f), maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                            val ungrouped = filteredRows.filter { it.group.isBlank() }
                            items(ungrouped, key = { "standing-ungrouped-" + it.team }) { row ->
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2)
                                    .clickable { }.padding(horizontal = dpx(12), vertical = dpx(10)), verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.rank, color = Accent2, fontWeight = FontWeight.Bold, modifier = Modifier.width(dpx(30)))
                                    if (row.logo.isNotBlank()) AsyncImage(row.logo, null, Modifier.size(dpx(30)), contentScale = ContentScale.Fit)
                                    Text(row.team, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(row.record, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            if (hubSection == "Multi-View") item(key = "multi-view") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Multi-View", color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("${multiViewChannels.size}/4 screens", color = Accent2, fontWeight = FontWeight.Bold)
                        Text("ADD SCREEN", color = if (multiViewChannels.size < 4) Color.White else Muted, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2).clickable { if (multiViewChannels.size < 4) showMultiViewPicker = true }.padding(horizontal = dpx(14), vertical = dpx(10)))
                    }
                    Text("Add up to four channels. Only the first screen plays audio.", color = Muted, fontSize = 13.sp)
                    if (multiViewChannels.isEmpty()) Text("Select ADD SCREEN to choose your first channel.", color = Muted)
                    else {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val gap = dpx(8)
                            val cellWidth = (maxWidth - gap) / 2
                            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                multiViewChannels.chunked(2).forEachIndexed { rowIndex, rowChannels ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                        rowChannels.forEachIndexed { colIndex, channel ->
                                            val index = rowIndex * 2 + colIndex
                                            Column(Modifier.width(cellWidth).clip(RoundedCornerShape(DS.RadiusM)).background(Color(0xFF101827)).padding(dpx(5))) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(channel.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                                    Text("×", color = Accent2, fontSize = 20.sp, modifier = Modifier.clickable { multiViewChannels = multiViewChannels.filterIndexed { i, _ -> i != index } }.padding(horizontal = dpx(6)))
                                                }
                                                SecondStream(channel, split = true, audioOn = index == 0, cropToFill = false, modifier = Modifier.fillMaxWidth().height(dpx(125)))
                                            }
                                        }
                                        if (rowChannels.size == 1) Spacer(Modifier.width(cellWidth))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (hubSection == "Scores" && nothing && slots != null && (allNow.isNotEmpty() || allSoon.isNotEmpty())) item(key = "none") {
                Column(verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Text("Keine Sendungen für diese Auswahl", color = Muted, fontSize = 18.sp)
                    FilterPill("Filter zurücksetzen", false) { sport = -1; win = 0 }
                }
            }
        }
        if (manageLeagues) {
            val firstLeagueFocus = remember { FocusRequester() }
            val firstActionFocus = remember { FocusRequester() }
            LaunchedEffect(manageLeagues) { if (manageLeagues) { delay(180); runCatching { firstActionFocus.requestFocus() } } }
            Dialog(onDismissRequest = { manageLeagues = false }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusL)).background(DS.Overlay2).padding(dpx(18)),
                    verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                    Text("Edit sports leagues", color = Color.White, fontSize = spx(21), fontWeight = FontWeight.Bold)
                    Text("Choose which leagues appear in Scores, My Teams, and Standings.", color = Muted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                        var selectAllFocused by remember { mutableStateOf(false) }
                        var clearAllFocused by remember { mutableStateOf(false) }
                        Box(Modifier.focusRequester(firstActionFocus).clip(RoundedCornerShape(DS.RadiusS)).background(if (selectAllFocused) Color(0xFF245B22) else DS.Overlay3)
                            .border(if (selectAllFocused) 3.dp else 1.dp, if (selectAllFocused) Color(0xFF9BE15D) else Color(0x5576B947), RoundedCornerShape(DS.RadiusS))
                            .onFocusChanged { selectAllFocused = it.isFocused }.clickable {
                                enabledLeagues.value = scoreLeagues.toSet()
                                context.getSharedPreferences("sports_hub", 0).edit().putStringSet("enabled_leagues", scoreLeagues.toMutableSet()).apply()
                                selectedScoreLeague = "All"
                            }.padding(horizontal = dpx(18), vertical = dpx(12))) {
                            Text("SELECT ALL", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.clip(RoundedCornerShape(DS.RadiusS)).background(if (clearAllFocused) Color(0xFF245B22) else DS.Overlay3)
                            .border(if (clearAllFocused) 3.dp else 1.dp, if (clearAllFocused) Color(0xFF9BE15D) else Color(0x5576B947), RoundedCornerShape(DS.RadiusS))
                            .onFocusChanged { clearAllFocused = it.isFocused }.clickable {
                                enabledLeagues.value = emptySet()
                                context.getSharedPreferences("sports_hub", 0).edit().putStringSet("enabled_leagues", mutableSetOf<String>()).apply()
                                selectedScoreLeague = "All"
                                selectedStandingsLeague = "NFL"
                                selectedFollowLeague = "NFL"
                            }.focusable().padding(horizontal = dpx(18), vertical = dpx(12))) {
                            Text("CLEAR ALL", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    LazyColumn(Modifier.height(dpx(360)), verticalArrangement = Arrangement.spacedBy(dpx(3))) {
                        items(scoreLeagues, key = { "edit-league-" + it }) { league ->
                            val checked = enabledLeagues.value.contains(league)
                            var leagueFocused by remember(league) { mutableStateOf(false) }
                            Row((if (league == scoreLeagues.first()) Modifier.focusRequester(firstLeagueFocus) else Modifier)
                                .fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
                                .background(if (leagueFocused) Color(0xFF245B22) else Color.Transparent)
                                .border(if (leagueFocused) 2.dp else 1.dp, if (leagueFocused) Color(0xFF9BE15D) else Color(0x225B8B50), RoundedCornerShape(DS.RadiusS))
                                .onFocusChanged { leagueFocused = it.isFocused }
                                .clickable {
                                    val next = enabledLeagues.value.toMutableSet().apply {
                                        if (!add(league)) remove(league)
                                    }
                                    enabledLeagues.value = next
                                    context.getSharedPreferences("sports_hub", 0).edit().putStringSet("enabled_leagues", next.toMutableSet()).apply()
                                    if (!next.contains(selectedScoreLeague)) selectedScoreLeague = "All"
                                    if (!next.contains(selectedStandingsLeague)) selectedStandingsLeague = next.firstOrNull() ?: "NFL"
                                    if (!next.contains(selectedFollowLeague)) selectedFollowLeague = next.firstOrNull() ?: "NFL"
                                }.padding(horizontal = dpx(10), vertical = dpx(11)),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(if (checked) "☑" else "☐", color = if (checked) Accent2 else Muted, fontSize = 21.sp)
                                Text(league, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = dpx(12)))
                            }
                        }
                    }
                    Text("DONE", color = Accent2, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.End).clip(RoundedCornerShape(DS.RadiusS))
                            .border(1.dp, Color(0xFF9BE15D), RoundedCornerShape(DS.RadiusS))
                            .clickable { manageLeagues = false }.padding(8.dp))
                }
            }
        }
        if (manageFollows) {
            Dialog(onDismissRequest = { manageFollows = false }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusL)).background(DS.Overlay2).padding(dpx(18)),
                    verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Text("Manage followed teams", color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold)
                    Text("LEAGUE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                        items(scoreLeagues, key = { "follow-league-" + it }) { league ->
                            FilterPill(league, selectedFollowLeague == league) { selectedFollowLeague = league; followTeamQuery = "" }
                        }
                    }
                    Field(followTeamQuery, { followTeamQuery = it }, "Find a team…", Modifier.fillMaxWidth())
                    val teams = followTeams.map { it.team }.distinct().filter { followTeamQuery.isBlank() || it.contains(followTeamQuery, true) }.sorted()
                    if (teams.isEmpty()) Text("No teams loaded for this league yet. Try another league or refresh.", color = Muted)
                    else LazyColumn(Modifier.fillMaxWidth().height(dpx(340)), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
                        items(teams, key = { "follow-team-" + it }) { team ->
                            val focusedTeam = favoriteTeams.value.contains(team)
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
                                .background(if (focusedTeam) Color(0x33245B22) else DS.Overlay1)
                                .border(1.dp, if (focusedTeam) Color(0xFF9BE15D) else DS.Overlay3, RoundedCornerShape(DS.RadiusS))
                                .clickable { toggleFavorite(team) }.padding(dpx(12)),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(team, color = Color.White, modifier = Modifier.weight(1f))
                                Text(if (focusedTeam) "✓ FOLLOWING" else "+ FOLLOW", color = Accent2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                    Text("DONE", color = Accent2, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.End).clickable { manageFollows = false }.padding(8.dp))
                }
            }
        }
        if (showMultiViewFullscreen) {
            Dialog(
                onDismissRequest = { showMultiViewFullscreen = false; hubSection = "Scores" },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (multiViewChannels.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Add a channel to start Multi-View", color = Color.White, fontSize = spx(20))
                        }
                    } else {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(dpx(8))) {
                            val gap = dpx(6)
                            val columns = if (multiViewChannels.size == 1) 1 else 2
                            val rows = (multiViewChannels.size + columns - 1) / columns
                            val cellWidth = (maxWidth - gap * (columns - 1)) / columns
                            val cellHeight = (maxHeight - gap * (rows - 1)) / rows
                            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                multiViewChannels.chunked(columns).forEachIndexed { rowIndex, rowChannels ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                        rowChannels.forEachIndexed { colIndex, channel ->
                                            val index = rowIndex * columns + colIndex
                                            Box(Modifier.width(cellWidth).height(cellHeight).clip(RoundedCornerShape(DS.RadiusS)).background(Color(0xFF101827))) {
                                                val selected = selectedMultiViewChannelId == channel.id || (selectedMultiViewChannelId == null && index == 0)
                                                SecondStream(channel, split = true, audioOn = selected, cropToFill = false, selected = selected, onSelect = { selectedMultiViewChannelId = channel.id }, modifier = Modifier.fillMaxSize())
                                                Text(channel.title, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 7.dp, vertical = 4.dp))
                                                Text("×", color = Color.White, fontSize = 20.sp,
                                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).clickable {
                                                        multiViewChannels = multiViewChannels.filterIndexed { i, _ -> i != index }
                                                        if (selectedMultiViewChannelId == channel.id) selectedMultiViewChannelId = multiViewChannels.firstOrNull { it.id != channel.id }?.id
                                                    }.padding(horizontal = 8.dp, vertical = 2.dp))
                                            }
                                        }
                                        if (rowChannels.size < columns) Spacer(Modifier.width(cellWidth))
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.align(Alignment.BottomEnd).padding(dpx(14)), horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                        Text("ADD SCREEN", color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(DS.RadiusS)).background(Accent1).clickable {
                                if (multiViewChannels.size < 4) { multiViewCategory = null; showMultiViewPicker = true }
                            }.padding(horizontal = dpx(14), vertical = dpx(10)))
                        Text("DONE", color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay3).clickable {
                                showMultiViewFullscreen = false; hubSection = "Scores"
                            }.padding(horizontal = dpx(14), vertical = dpx(10)))
                    }
                }
            }
        }
        if (showMultiViewPicker) {
            val categoryChannels = live.filter { (it.group.ifBlank { "Other" }) == (multiViewCategory ?: multiViewCategories.firstOrNull()) }
            Dialog(onDismissRequest = { showMultiViewPicker = false }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusL)).background(DS.Overlay2).padding(dpx(18)),
                    verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                    Text("Add a screen · Choose category", color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold)
                    Box {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
                                .background(DS.Overlay3).clickable { multiViewCategoryMenu = true }
                                .padding(horizontal = dpx(14), vertical = dpx(12)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(multiViewCategory ?: multiViewCategories.firstOrNull() ?: "No categories", color = Color.White, modifier = Modifier.weight(1f))
                            Text("▾", color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        if (multiViewCategoryMenu) {
                            LazyColumn(
                                Modifier.fillMaxWidth().height(dpx(180))
                                    .clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay3)
                            ) {
                                items(multiViewCategories, key = { "multi-category-option-" + it }) { category ->
                                    Text(
                                        category, color = Color.White,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            multiViewCategory = category
                                            multiViewCategoryMenu = false
                                        }.padding(horizontal = dpx(14), vertical = dpx(11))
                                    )
                                }
                            }
                        }
                    }
                    val availableChannels = categoryChannels.filterNot { channel -> multiViewChannels.any { it.id == channel.id } }
                    if (availableChannels.isEmpty()) {
                        Text("No channels in this category are available to add.", color = Muted)
                    } else LazyColumn(Modifier.height(dpx(360)), verticalArrangement = Arrangement.spacedBy(dpx(5))) {
                        items(availableChannels, key = { "multi-pick-" + it.id }) { channel ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS)).clickable {
                                multiViewChannels = (multiViewChannels + channel).take(4)
                                if (selectedMultiViewChannelId == null) selectedMultiViewChannelId = channel.id
                                showMultiViewPicker = false
                            }.padding(dpx(10)), verticalAlignment = Alignment.CenterVertically) {
                                Text(channel.title, color = Color.White, modifier = Modifier.weight(1f))
                                Text("ADD", color = Accent2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                    Text("CANCEL", color = Accent2, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.End).clickable { showMultiViewPicker = false }.padding(8.dp))
                }
            }
        }
        gameCenterGame?.let { game ->
            Dialog(onDismissRequest = { gameCenterGame = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Column(Modifier.fillMaxWidth(0.92f).heightIn(max = dpx(620))
                    .clip(RoundedCornerShape(DS.RadiusL)).background(DS.Overlay2)
                    .border(2.dp, Color(0xFF557B48), RoundedCornerShape(DS.RadiusL)).padding(dpx(18)),
                    verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("GAME CENTER", color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                            Text(game.name, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold)
                            Text(game.league + " · " + game.status, color = Muted, fontSize = 13.sp)
                        }
                        Text("✕", color = Color.White, modifier = Modifier.clickable { gameCenterGame = null }.padding(8.dp))
                    }
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                        item {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(Color(0xCC17243D)).padding(dpx(14)),
                                verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                                ScoreTeamRow(game.away, game.awayLogo, game.awayScore, game.awayRecord)
                                ScoreTeamRow(game.home, game.homeLogo, game.homeScore, game.homeRecord)
                                Text(listOf(game.detail, game.period.takeIf { it.isNotBlank() }?.let { "Period $it" }.orEmpty(), game.clock).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 13.sp)
                                if (game.awayPeriodScores.isNotEmpty() || game.homePeriodScores.isNotEmpty()) ScoreQuarterTable(game)
                            }
                        }
                        if (game.lastPlay.isNotBlank()) item {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(DS.Overlay2).padding(dpx(14))) {
                                Text("LATEST PLAY", color = Accent2, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                Text(game.lastPlay, color = Color.White, fontSize = 14.sp)
                            }
                        }
                        if (game.footballPlay.isNotBlank()) item {
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(DS.Overlay2).padding(dpx(14))) {
                                Text("PLAY DETAILS", color = Accent2, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                Text(game.footballPlay, color = Color.White, fontSize = 14.sp)
                                if (game.redZone) Text("RED ZONE", color = Color(0xFFFF5A5F), fontWeight = FontWeight.Bold)
                            }
                        }
                        item { Text("More detailed box scores and play-by-play will appear when the sports data feed supplies them.", color = Muted, fontSize = 12.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                        GameActionButton("WATCH GAME", "Normal player", AIcon.Play, {
                            gameCenterGame = null
                            playScoreGame(game)
                        }, Modifier.weight(1f))
                        GameActionButton("WATCH + INFO", "Video with panel", AIcon.Info, {
                            gameCenterGame = null
                            playScoreGame(game)
                        }, Modifier.weight(1f))
                    }
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
            .background(if (f) Color(0xFF245B22) else Color(0x14FFFFFF))
            .border(if (f) 3.dp else 1.dp, if (f) Color(0xFF9BE15D) else Color(0x1AFFFFFF), shape)
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
            .background(if (f) Color(0xFF245B22) else Color(0x14FFFFFF))
            .border(if (f) 3.dp else 1.dp, if (f) Color(0xFF9BE15D) else Color(0x1AFFFFFF), shape)
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
private fun ScoreGameCard(game: SportLogic.ScoreGame, onClick: () -> Unit, onGameCenter: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(DS.RadiusM)
    Row(
        Modifier.width(dpx(430)).clip(shape).background(Color(0x1AFFFFFF))
            .border(1.dp, Color(0x3376B947), shape).padding(dpx(12)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(10))
    ) {
        Column(Modifier.weight(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = dpx(3)), verticalArrangement = Arrangement.spacedBy(dpx(8))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(game.league, color = Accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(when (game.state.lowercase()) { "in" -> "LIVE"; "post" -> "FINAL"; else -> game.detail },
                    color = if (game.state.equals("in", true)) PT.Live else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            val football = game.league == "NFL" || game.league == "College Football"
            ScoreTeamRow(game.away, game.awayLogo, game.awayScore, hasBall = football && game.possessionTeamId.isNotBlank() && game.possessionTeamId == game.awayTeamId)
            ScoreTeamRow(game.home, game.homeLogo, game.homeScore, hasBall = football && game.possessionTeamId.isNotBlank() && game.possessionTeamId == game.homeTeamId)
            if (football && game.footballPlay.isNotBlank()) Text(game.footballPlay, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(game.status, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.width(dpx(112)), verticalArrangement = Arrangement.spacedBy(dpx(8))) {
            GameActionButton("GAME", "Watch live", AIcon.Play, onClick, Modifier)
            GameActionButton("GAME CENTER", "Scores & stats", AIcon.Info, onGameCenter, Modifier)
        }
    }
}

@Composable
private fun GameActionButton(title: String, subtitle: String, icon: AIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
        .background(if (focused) Color(0xFF245B22) else Color(0xFF202A24))
        .border(if (focused) 3.dp else 1.dp, if (focused) Color(0xFF9BE15D) else Color(0x5576B947), RoundedCornerShape(DS.RadiusS))
        .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick)
        .padding(horizontal = dpx(8), vertical = dpx(9)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(3))) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(dpx(18)))
        Text(title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(subtitle, color = Color(0xFFB8C8B8), fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun FeaturedGamePanel(game: SportLogic.ScoreGame, previewChannel: Item?, onOpen: () -> Unit, onGameCenter: () -> Unit) {
    var watchFocused by remember { mutableStateOf(false) }
    var centerFocused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(dpx(310)), horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
        // Avoid a second video decoder inside the scrolling hub; it caused severe lag on TV devices.
        Box(Modifier.weight(1.15f).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusM))
            .background(Brush.linearGradient(listOf(Color(0xFF173E55), Color(0xFF101A35))))
            .border(if (watchFocused) 3.dp else 1.dp, if (watchFocused) Color(0xFF9BE15D) else Color(0x337FA9D8), RoundedCornerShape(DS.RadiusM))
            .onFocusChanged { watchFocused = it.isFocused }.focusable().clickable(onClick = onOpen),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                Text(if (game.state.equals("in", true)) "● LIVE GAME" else "GAME CENTER", color = PT.Live, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text(game.league, color = Color.White, fontSize = spx(24), fontWeight = FontWeight.Bold)
                Text(if (previewChannel != null) "▶  WATCH " + previewChannel.title else "No matching sports channel", color = if (previewChannel != null) Color.White else Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusM))
            .background(Color(0xCC17243D)).border(1.dp, Color(0x337FA9D8), RoundedCornerShape(DS.RadiusM)).padding(dpx(14)),
            verticalArrangement = Arrangement.spacedBy(dpx(8))) {
            Text(game.league.uppercase(), color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(game.name, color = Color.White, fontSize = spx(18), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val football = game.league == "NFL" || game.league == "College Football"
            ScoreTeamRow(game.away, game.awayLogo, game.awayScore, game.awayRecord, football && game.possessionTeamId.isNotBlank() && game.possessionTeamId == game.awayTeamId)
            ScoreTeamRow(game.home, game.homeLogo, game.homeScore, game.homeRecord, football && game.possessionTeamId.isNotBlank() && game.possessionTeamId == game.homeTeamId)
            Text(listOf(game.status, game.detail, if (game.period.isNotBlank()) "Period ${game.period}" else "", if (game.clock.isNotBlank()) game.clock else "").filter { it.isNotBlank() }.distinct().joinToString(" · "), color = if (game.state.equals("in", true)) PT.Live else Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (game.awayPeriodScores.isNotEmpty() || game.homePeriodScores.isNotEmpty()) ScoreQuarterTable(game)
            if (football && game.footballPlay.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
                        .background(Color(0x332B9B65)).padding(horizontal = dpx(10), vertical = dpx(7)),
                    verticalArrangement = Arrangement.spacedBy(dpx(2))
                ) {
                    Text("DOWN & DISTANCE", color = Accent2, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Text(game.footballPlay, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (football && game.redZone) Text("🔴 RED ZONE", color = Color(0xFFFF5A5F), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dpx(8))) {
                GameActionButton("GAME", "Watch live", AIcon.Play, onOpen, Modifier.weight(1f))
                GameActionButton("GAME CENTER", "Scores & stats", AIcon.Info, onGameCenter, Modifier.weight(1f))
            }
            if (game.lastPlay.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(dpx(3))) {
                Text("LAST PLAY", color = Accent2, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text(game.lastPlay, color = Color.White, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ScoreQuarterTable(game: SportLogic.ScoreGame) {
    val count = maxOf(game.awayPeriodScores.size, game.homePeriodScores.size)
    val labels = (1..count).map { "Q$it" } + "T"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("PER", color = Muted, fontSize = 10.sp, modifier = Modifier.width(34.dp))
        labels.forEachIndexed { index, label ->
            Text(label, color = if (index == labels.lastIndex) Accent2 else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
    }
    listOf(game.awayPeriodScores to game.awayScore, game.homePeriodScores to game.homeScore).forEachIndexed { teamIndex, pair ->
        val periods = pair.first
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (teamIndex == 0) "AWY" else "HME", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(34.dp))
            repeat(count) { index -> Text(periods.getOrNull(index) ?: "—", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f)) }
            Text(pair.second, color = Accent2, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ScoreTeamRow(name: String, logo: String, score: String, record: String = "", hasBall: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(dpx(38)).clip(RoundedCornerShape(DS.RadiusXs)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
            if (logo.isNotBlank()) AsyncImage(logo, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
            else Text(name.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasBall) Text("🏈", fontSize = 14.sp)
            Text(name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (record.isNotBlank()) Text("($record)", color = Muted, fontSize = 11.sp, maxLines = 1)
        }
        Text(score, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}
