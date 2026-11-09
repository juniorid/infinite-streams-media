package com.ipvio.stream

import android.widget.Toast

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
    val slots by produceState<Pair<List<SportLogic.Slot>, List<SportLogic.Slot>>?>(null, sportsInput, tick, Xmltv.channelCount, hubSection) {
        if (hubSection == "Scores") value = withContext(Dispatchers.Default) {
            SportLogic.slots(sportsInput, { Xmltv.forChannel(it.tvgId) }, System.currentTimeMillis() / 1000, 48 * 3600L, 300)
        }
    }
    val allNow = slots?.first.orEmpty()
    val allSoon = slots?.second.orEmpty()
    val scoreLeagues = listOf("NFL", "College Football", "NBA", "WNBA", "Men's College Basketball", "MLB", "NHL", "MLS", "Premier League", "Champions League", "UFC", "Tennis", "Golf", "Formula 1", "Cricket")
    val enabledLeagues = remember(context) {
        mutableStateOf(context.getSharedPreferences("sports_hub", 0)
            .getStringSet("enabled_leagues", scoreLeagues.toMutableSet())?.toSet() ?: scoreLeagues.toSet())
    }
    var scoreRefresh by remember { mutableIntStateOf(0) }
    // Scores uses the enabled filter; My Teams/Manage Follows always loads all leagues so Clear All
    // never makes the follow manager empty or impossible to navigate.
    val scoreGames by produceState<List<SportLogic.ScoreGame>>(emptyList(), scoreRefresh, enabledLeagues.value, hubSection, manageFollows) {
        val leaguesToLoad = if (hubSection == "My teams" || manageFollows) scoreLeagues.toSet() else enabledLeagues.value
        value = withContext(IO) { SportLogic.fetchScoreboards(leaguesToLoad) }
    }
    val activeScoreLeagues = scoreLeagues.filter { enabledLeagues.value.contains(it) }
    var manageLeagues by remember { mutableStateOf(false) }
    val editLeaguesFocus = remember { FocusRequester() }
    val selectAllLeaguesFocus = remember { FocusRequester() }
    val clearAllLeaguesFocus = remember { FocusRequester() }
    val doneLeaguesFocus = remember { FocusRequester() }
    val manageFollowsFocus = remember { FocusRequester() }
    val firstFollowLeagueFocus = remember { FocusRequester() }
    val doneFollowsFocus = remember { FocusRequester() }
    var editLeagueFocused by remember { mutableStateOf(false) }
    var todayFocused by remember { mutableStateOf(false) }
    var refreshFocused by remember { mutableStateOf(false) }
    var followsButtonFocused by remember { mutableStateOf(false) }
    LaunchedEffect(manageLeagues) {
        if (manageLeagues) {
            Dialog(onDismissRequest = { manageLeagues = false }) {
                Column(Modifier.fillMaxWidth().height(dpx(590)).clip(RoundedCornerShape(DS.RadiusL)).background(DS.Overlay2).padding(dpx(18)),
                    verticalArrangement = Arrangement.spacedBy(dpx(10))) {
                    Text("Edit sports leagues", color = Color.White, fontSize = spx(21), fontWeight = FontWeight.Bold)
                    Text("Choose which leagues appear in Scores, My Teams, and Standings.", color = Muted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                        PillButton("Select all", Modifier.focusRequester(selectAllLeaguesFocus), light = true) {
                            enabledLeagues.value = scoreLeagues.toSet()
                            context.getSharedPreferences("sports_hub", 0).edit().putStringSet("enabled_leagues", scoreLeagues.toMutableSet()).apply()
                        }
                        PillButton("Clear all", Modifier.focusRequester(clearAllLeaguesFocus)) {
                            enabledLeagues.value = emptySet()
                            context.getSharedPreferences("sports_hub", 0).edit().putStringSet("enabled_leagues", mutableSetOf<String>()).apply()
                            selectedScoreLeague = "All"
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dpx(3))) {
                        items(scoreLeagues, key = { "edit-league-" + it }) { league ->
                            val checked = enabledLeagues.value.contains(league)
                            var leagueFocused by remember(league) { mutableStateOf(false) }
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
                                .background(if (leagueFocused) Color(0xFF245B22) else Color.Transparent)
                                .border(if (leagueFocused) 2.dp else 1.dp, if (leagueFocused) Color(0xFF9BE15D) else Color(0x225B8B50), RoundedCornerShape(DS.RadiusS))
                                .onFocusChanged { leagueFocused = it.isFocused }
                                .clickable {
                                    val next = enabledLeagues.value.toMutableSet().apply { if (!add(league)) remove(league) }
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        PillButton("Done", Modifier.focusRequester(doneLeaguesFocus), light = true) { manageLeagues = false }
                    }
                }
            }
        }
        if (manageFollows) {
            Dialog(onDismissRequest = { manageFollows = false }) {
                Column(Modifier.fillMaxWidth().height(dpx(570)).clip(RoundedCornerShape(DS.RadiusL)).background(DS.Overlay2).padding(dpx(18)),
                    verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                    Text("Manage followed teams", color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold)
                    Text("Choose a league, then select teams to follow.", color = Muted, fontSize = 13.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(dpx(8)), contentPadding = PaddingValues(vertical = dpx(4))) {
                        items(scoreLeagues, key = { "follow-league-" + it }) { league ->
                            SportLeagueChip(league, selectedFollowLeague == league,
                                if (league == scoreLeagues.first()) Modifier.focusRequester(firstFollowLeagueFocus) else Modifier
                            ) { selectedFollowLeague = league }
                        }
                    }
                    val teams = scoreGames.filter { it.league == selectedFollowLeague }
                        .flatMap { listOf(it.away, it.home) }.distinct().sorted()
                    if (teams.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Loading teams or no games listed for this league. Try Refresh scores.", color = Muted)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dpx(4))) {
                            items(teams, key = { "follow-team-" + it }) { team ->
                                var teamFocused by remember(team) { mutableStateOf(false) }
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS))
                                    .background(if (teamFocused) Color(0xFF245B22) else Color.Transparent)
                                    .border(if (teamFocused) 2.dp else 1.dp, if (teamFocused) Color(0xFF9BE15D) else Color(0x225B8B50), RoundedCornerShape(DS.RadiusS))
                                    .onFocusChanged { teamFocused = it.isFocused }
                                    .clickable { toggleFavorite(team) }
                                    .padding(dpx(10)), verticalAlignment = Alignment.CenterVertically) {
                                    Text(team, color = Color.White, modifier = Modifier.weight(1f))
                                    Text(if (favoriteTeams.value.contains(team)) "✓ FOLLOWING" else "+ FOLLOW", color = Accent2, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("↻ Refresh scores", color = Accent2, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(DS.RadiusS)).clickable { scoreRefresh++ }.padding(8.dp))
                        PillButton("Done", Modifier.focusRequester(doneFollowsFocus), light = true) { manageFollows = false }
                    }
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
private fun SportLeagueChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember(label) { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        modifier = modifier.clip(shape)
            .background(if (focused || selected) Color(0xFF245B22) else DS.Overlay2)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color(0xFF9BE15D) else Color(0x5576B947), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(12), vertical = dpx(8)))
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
private fun ScoreGameCard(game: SportLogic.ScoreGame, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(DS.RadiusM)
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(dpx(300)).clip(shape)
            .background(if (focused) Color(0xFF245B22) else Color(0x1AFFFFFF))
            .border(if (focused) 3.dp else 1.dp, if (focused) Color(0xFF9BE15D) else Color(0x1AFFFFFF), shape)
            .onFocusChanged { focused = it.isFocused }
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
        val football = game.league == "NFL" || game.league == "College Football"
        ScoreTeamRow(game.away, game.awayLogo, game.awayScore, hasBall = football && game.possessionTeamId.isNotBlank() && game.possessionTeamId == game.awayTeamId)
        ScoreTeamRow(game.home, game.homeLogo, game.homeScore, hasBall = football && game.possessionTeamId.isNotBlank() && game.possessionTeamId == game.homeTeamId)
        if (football && game.footballPlay.isNotBlank()) Text(game.footballPlay, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (football && game.redZone) Text("🔴 RED ZONE", color = Color(0xFFFF5A5F), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        Text(game.status, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeaturedGamePanel(game: SportLogic.ScoreGame, previewChannel: Item?, onOpen: () -> Unit) {
    var watchFocused by remember { mutableStateOf(false) }
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
