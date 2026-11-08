package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val S_SORT_AZ = 6
private const val S_SORT_PLAYLIST = 8

/** Index der aktuellen Liste – bleibt beim Verlassen und Wiederkommen erhalten. */
private object SearchCache {
    private var idx: SearchIndex? = null
    @Synchronized fun get(all: List<Item>): SearchIndex? = idx?.takeIf { it.all === all }
    @Synchronized fun build(all: List<Item>): SearchIndex = get(all) ?: SearchIndex(all).also { idx = it }
}

/** Suche (Tab 4): "Zuletzt geschaut" getrennt nach Filme, Serien und Live TV. */
@Composable
fun SearchScreen(
    tab: Int, state: LazyListState,
    onOpen: (Item) -> Unit, onBrowse: (BrowseReq) -> Unit, onTab: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = PlaylistStore.channels
    // Index im Hintergrund bauen; Suchen laufen ebenfalls im Hintergrund und erst nach einer kurzen Tipp-Pause
    val index by produceState(SearchCache.get(all), all) { value = withContext(Dispatchers.Default) { SearchCache.build(all) } }
    val byId = index?.byId.orEmpty()
    val hist = History.ids.mapNotNull { byId[it] }
    val cardW = AppSettings.cardW
    val firstRes = remember { FocusRequester() }
    val firstHistory = remember { FocusRequester() }
    val sideFocus = remember { FocusRequester() }
    var inSidebar by remember { mutableStateOf(false) }

    SideEffect { NavState.tab = tab; ContentFocus.tab = tab }
    var canVoice by remember { mutableStateOf(VoiceSearch.available(ctx)) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            SearchState.query = it
            AppSettings.addSearch(ctx, it)
        }
    }
    var fieldFocused by remember { mutableStateOf(false) }
    // Beim Öffnen der Suche sofort ins Suchfeld (nicht beim Zurückkommen von einer Detailseite: dann zum gewählten Titel).
    // Erst aufhören, wenn das Feld den Fokus WIRKLICH hat – die Seitenleiste hält ihn beim Wechsel noch kurz fest.
    LaunchedEffect(Unit) {
        if (LastFocus.pending != null) return@LaunchedEffect
        AppNav.focusTab = -1
        repeat(20) {
            delay(100)
            if (fieldFocused) return@LaunchedEffect
            runCatching { ContentFocus.req.requestFocus() }
        }
    }
    BackHandler(enabled = inSidebar && tab != 0) { onTab(0) }

    val q = SearchState.query.trim()
    val none = Triple(emptyList<Item>(), emptyList<Item>(), emptyList<Item>())
    // Ergebnis gehört immer zu genau einer Eingabe: solange noch gesucht wird, steht "Suche …" statt "Keine Treffer"
    val found by produceState("" to none, q, index) {
        val idx = index ?: return@produceState
        if (q.length < 2) { value = q to none; return@produceState }
        delay(250)
        value = q to withContext(Dispatchers.Default) { idx.find(q) }
    }
    val searching = q.length >= 2 && found.first != q
    val (rm0, rs, rl) = if (q.length < 2 || searching) none else found.second
    // Derselbe Film in mehreren Fassungen nur einmal (Quelle wählt man auf der Detailseite)
    val rm: List<Item> = remember(rm0) { PlaylistLogic.firstPerKey(rm0) { it.mk + "|" + it.lang } }
    val firstKey = when { rm.isNotEmpty() -> "rm"; rs.isNotEmpty() -> "rs"; rl.isNotEmpty() -> "rl"; else -> "" }
    // Zurück: im Suchfeld (Tastatur schon zu) erst zu den letzten Suchanfragen bzw. Treffern, sonst in die Seitenleiste
    BackHandler(enabled = !inSidebar) {
        val target = when {
            !fieldFocused -> null
            firstKey.isNotEmpty() -> firstRes
            q.length < 2 && AppSettings.searchHistory.isNotEmpty() -> firstHistory
            else -> null
        }
        val moved = target != null && runCatching { target.requestFocus() }.isSuccess
        if (!moved) runCatching { sideFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().sidebarPad()) {
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 48.dp)) {
                item { Spacer(Modifier.height(24.dp)) }
                item {
                    Box(
                        Modifier.padding(horizontal = DS.PageX, vertical = 8.dp).onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown &&
                                (firstKey.isNotEmpty() || (q.length < 2 && AppSettings.searchHistory.isNotEmpty()))
                            ) {
                                runCatching { (if (firstKey.isNotEmpty()) firstRes else firstHistory).requestFocus() }; true
                            } else false
                        }
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Field(
                                SearchState.query, { SearchState.query = it }, "Filme, Serien oder Sender suchen…",
                                Modifier.weight(1f), inner = Modifier.focusRequester(ContentFocus.req).onFocusChanged { fieldFocused = it.isFocused }
                            )
                            // Sprachsuche: System-Spracherkennung, wo erlaubt. „Mit dem Handy suchen“ ist entfallen (Runde 14):
                            // Fire TV hat dafür eine eigene App.
                            if (canVoice) PillButton("Sprechen", icon = AIcon.Mic) {
                                runCatching { voice.launch(VoiceSearch.intent()) }.onFailure { canVoice = false }
                            }
                        }
                    }
                }
                if (q.length < 2) {
                    if (AppSettings.searchHistory.isNotEmpty()) item(key = "sh") {
                        Column {
                            SectionHeader("Letzte Suchanfragen")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                itemsIndexed(AppSettings.searchHistory.toList()) { index, term ->
                                    PillButton(term, if (index == 0) Modifier.focusRequester(firstHistory) else Modifier, icon = AIcon.History, raw = true) { SearchState.query = term }
                                }
                                item { SmallPill("Verlauf löschen") { AppSettings.clearSearch(ctx) } }
                            }
                        }
                    }
                    val hm = hist.filter { it.kind == Kind.MOVIE }
                    val hs = hist.filter { it.kind == Kind.SERIES }
                    val hl = hist.filter { it.kind == Kind.LIVE }
                    if (hm.isNotEmpty()) item(key = "hm") {
                        SearchRow("Zuletzt geschaut · Filme", hm.take(30), cardW, onOpen) {
                            onBrowse(BrowseReq("Zuletzt geschaut · Filme", hm, S_SORT_PLAYLIST))
                        }
                    }
                    if (hs.isNotEmpty()) item(key = "hs") {
                        SearchRow("Zuletzt geschaut · Serien", hs.take(30), cardW, onOpen) {
                            onBrowse(BrowseReq("Zuletzt geschaut · Serien", hs, S_SORT_PLAYLIST))
                        }
                    }
                    if (hl.isNotEmpty()) item(key = "hl") {
                        SearchRow("Zuletzt geschaut · Live TV", hl.take(30), cardW, onOpen) {
                            onBrowse(BrowseReq("Zuletzt geschaut · Live TV", hl, S_SORT_PLAYLIST))
                        }
                    }
                } else {
                    val openS: (Item) -> Unit = { i -> AppSettings.addSearch(ctx, q); onOpen(i) }
                    if (searching) item {
                        Text("Suche …", color = Muted, fontSize = 16.sp, modifier = Modifier.padding(horizontal = DS.PageX, vertical = 16.dp))
                    } else if (rm.isEmpty() && rs.isEmpty() && rl.isEmpty()) item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            EmptyState(AIcon.Info, "Keine Treffer", "Prüfe die Schreibweise oder suche nach einem Teil des Titels. Kleine Tippfehler findet die Suche trotzdem.")
                        }
                    }
                    if (rm.isNotEmpty()) item(key = "rm") {
                        SearchRow("Filme", rm, cardW, openS, if (firstKey == "rm") firstRes else null) {
                            AppSettings.addSearch(ctx, q); onBrowse(BrowseReq("Suche „$q“ · Filme", rm, S_SORT_AZ))
                        }
                    }
                    if (rs.isNotEmpty()) item(key = "rs") {
                        SearchRow("Serien", rs, cardW, openS, if (firstKey == "rs") firstRes else null) {
                            AppSettings.addSearch(ctx, q); onBrowse(BrowseReq("Suche „$q“ · Serien", rs, S_SORT_AZ))
                        }
                    }
                    if (rl.isNotEmpty()) item(key = "rl") {
                        SearchRow("Live TV", rl, cardW, openS, if (firstKey == "rl") firstRes else null) {
                            AppSettings.addSearch(ctx, q); onBrowse(BrowseReq("Suche „$q“ · Live TV", rl, S_SORT_AZ))
                        }
                    }
                }
            }
        }
        Sidebar(
            tab,
            { id -> if (id == tab) scope.launch { state.scrollToItem(0) }; onTab(id) },
            Modifier.align(SidebarPrefs.align()), sideFocus
        ) { inSidebar = it }
    }
}

@Composable
private fun SearchRowHeader(title: String, count: Int, headerReq: FocusRequester, firstCard: FocusRequester, rowState: androidx.compose.foundation.lazy.LazyListState, onAll: () -> Unit) {
    val sc = rememberCoroutineScope()
    val key = NavState.tab.toString() + "|" + title
    LaunchedEffect(Unit) {
        if (PillMemory.key == key) {
            delay(250)
            runCatching { headerReq.requestFocus() }
            PillMemory.key = null
        }
    }
    Row(Modifier.padding(start = DS.PageX, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallPill("Alle $count", Modifier.focusRequester(headerReq).onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                // Erste Karte nicht im Bild (Reihe seitlich verschoben) oder noch nicht aufgebaut: erst an den Anfang, dann markieren.
                // Vorher blieb der Fokus hier hängen, weil der Fehlschlag verschluckt wurde.
                if (runCatching { firstCard.requestFocus() }.isFailure) sc.launch {
                    rowState.scrollToItem(0)
                    repeat(3) { withFrameNanos { } }
                    runCatching { firstCard.requestFocus() }
                }
                true
            } else false
        }, trailing = AIcon.Right) { PillMemory.key = key; onAll() }
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Accent1))
        Spacer(Modifier.width(10.dp))
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SearchAllTile(live: Boolean, w: Dp, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.08f) else 1f, label = "searchalltile")
    if (live) {
        val shape = RoundedCornerShape(DS.RadiusM)
        Column(
            Modifier.width(w).graphicsLayer { scaleX = s; scaleY = s }.focusGlow(f, shape).clip(shape).background(Surf)
                .border(DS.FocusBorder, if (f) Accent2 else Surf2, shape)
                .onFocusChanged { f = it.isFocused }
                .clickable(onClick = onClick)
        ) {
            Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                IconLabel(null, "Alle anzeigen", Color.White, 17.sp, FontWeight.Bold, trailing = AIcon.Right)
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(" ", fontSize = 15.sp, maxLines = 1)
                Text(" ", fontSize = 13.sp, maxLines = 1)
            }
        }
    } else {
        val shape = RoundedCornerShape(DS.RadiusM)
        Column(
            Modifier.width(w).graphicsLayer { scaleX = s; scaleY = s }
                .onFocusChanged { f = it.isFocused }
                .clickable(onClick = onClick)
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusGlow(f, shape).clip(shape).background(Surf)
                    .border(DS.FocusBorder, if (f) Accent2 else Surf2, shape),
                contentAlignment = Alignment.Center
            ) {
                IconLabel(null, "Alle anzeigen", Color.White, 18.sp, FontWeight.Bold, trailing = AIcon.Right)
            }
            Text(" ", fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
            Text(" ", fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SearchRow(
    title: String, list: List<Item>, cardW: Int, onOpen: (Item) -> Unit,
    firstReq: FocusRequester? = null, onAll: () -> Unit
) {
    val rowState = RowStates.get("${NavState.tab}|$title")
    val liveRow = list.firstOrNull()?.kind == Kind.LIVE
    val headerReq = remember { FocusRequester() }
    val firstCard = firstReq ?: remember { FocusRequester() }
    Column {
        SearchRowHeader(title, list.size, headerReq, firstCard, rowState, onAll)
        Box(
            Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && e.nativeKeyEvent.repeatCount == 0) {
                    runCatching { headerReq.requestFocus() }.isSuccess
                } else false
            }
        ) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = DS.PageX, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                itemsIndexed(list.take(60)) { i, x ->
                    val fr = if (i == 0) Modifier.focusRequester(firstCard) else Modifier
                    if (x.kind == Kind.LIVE) LiveCard(x, { onOpen(x) }, Modifier.width((cardW * 4 / 3).dp).then(fr))
                    else PosterCard(x, { onOpen(x) }, Modifier.width(cardW.dp).restoreFocus(x.id).then(fr))
                }
                item { SearchAllTile(liveRow, if (liveRow) (cardW * 4 / 3).dp else cardW.dp, onAll) }
            }
        }
    }
}
