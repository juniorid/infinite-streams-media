package com.ipvio.stream

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

data class BrowseReq(val title: String, val items: List<Item>, val sort: Int = 0)

class BrowseState(val req: BrowseReq) {
    var sort by mutableIntStateOf(req.sort.coerceIn(0, 8))
    var minRi by mutableIntStateOf(0)
    var year by mutableStateOf("Alle")
    var genre by mutableStateOf<String?>(null)
    var country by mutableStateOf<String?>(null)
    /** Quelle bei mehreren Playlists: -1 = alle, sonst Index in [PlaylistStore.sourceNames]. */
    var source by mutableIntStateOf(-1)
    var focused by mutableIntStateOf(0)
    /** Stand der Genre-Erkennung, mit dem sortiert wurde. Neue Infos sortieren erst auf Wunsch neu (kein Umsortieren unter dem Cursor). */
    var ver by mutableIntStateOf(Classifier.version)
    // Zuletzt berechnetes Ergebnis: Rückkehr von der Detailseite zeigt sofort wieder dieselbe Liste
    internal var lastKey: BrowseKey? = null
    internal var lastList: List<Item>? = null
    internal var lastFacets: Pair<Int, Facets>? = null
}

internal data class BrowseKey(val sort: Int, val minRi: Int, val year: String, val genre: String?, val country: String?, val ver: Int, val source: Int = -1)

/** Auswahl für die Filter (Genres, Länder, Jahre) – aus den Titeln der Liste. */
internal class Facets(val genres: List<String>, val countries: List<Pair<String, String>>, val years: List<String>)

object BrowsePrefs {
    var detail by mutableStateOf(true)
    private var inited = false
    fun init(c: Context) {
        if (inited) return
        inited = true
        // Runde 18: Kino ist die Standardansicht (neuer Schlüssel "detail2", damit die alte Voreinstellung "Raster" nicht festhängt)
        detail = Profiles.sp(c, "ipvio_browse").getBoolean("detail2", true)
    }
    fun set(c: Context, v: Boolean) {
        detail = v
        Profiles.sp(c, "ipvio_browse").edit().putBoolean("detail2", v).apply()
    }
}

private val SORTS = listOf("Neu hinzugefügt", "Beliebtheit", "Rating: beste zuerst", "Rating: schwächste zuerst", "Jahr: neueste zuerst", "Jahr: älteste zuerst", "A–Z", "Z–A", "Playlist")
private val RATING_LABELS = listOf("Alle", "≥ 5", "≥ 6", "≥ 7", "≥ 8", "≥ 9")
private val RATING_VALUES = listOf(0.0, 5.0, 6.0, 7.0, 8.0, 9.0)
private val DECADES = listOf("2020er", "2010er", "2000er", "1990er", "vor 1990")

/** Sortiert nach einem Schlüssel, der pro Titel nur EINMAL berechnet wird (nicht bei jedem Vergleich). */
private fun <K : Comparable<K>> List<Item>.sortedByCached(descending: Boolean, key: (Item) -> K): List<Item> {
    val keyed = map { it to key(it) }
    val sorted = if (descending) keyed.sortedByDescending { it.second } else keyed.sortedBy { it.second }
    return sorted.map { it.first }
}

private fun yearOf(i: Item): Int = MetaRepo.cached(i)?.year?.toIntOrNull() ?: i.year ?: 0

private fun yearMatches(label: String, y: Int): Boolean = when (label) {
    "Alle" -> true
    "2020er" -> y in 2020..2029
    "2010er" -> y in 2010..2019
    "2000er" -> y in 2000..2009
    "1990er" -> y in 1990..1999
    "vor 1990" -> y in 1..1989
    else -> y == label.toIntOrNull()
}

private fun countryName(code: String): String =
    Locale.Builder().setRegion(code).build().getDisplayCountry(Lang.locale()).ifBlank { code }

private fun facetsOf(items: List<Item>): Facets {
    val g = HashSet<String>(); val c = HashSet<String>(); val y = HashSet<Int>()
    for (i in items) {
        val m = MetaRepo.cached(i)
        if (m != null) { g.addAll(Genres.categoriesOf(m)); c.addAll(m.countries) }
        val yr = yearOf(i)
        if (yr > 0) y.add(yr)
    }
    c.remove("--")
    return Facets(
        g.sortedWith(String.CASE_INSENSITIVE_ORDER),
        c.map { it to countryName(it) }.sortedBy { it.second.lowercase() },
        y.sortedDescending().map { it.toString() }
    )
}

/** Nur Titel einer Quelle; doppelte Filme werden durch die Fassung dieser Quelle ersetzt. */
private fun ofSource(items: List<Item>, k: Int): List<Item> {
    if (k < 0) return items
    val seen = HashSet<String>()
    return items.mapNotNull { i -> (if (PlaylistStore.inSource(i, k)) i else PlaylistStore.twin(i, k))?.takeIf { seen.add(it.id) } }
}

private fun filterAndSort(items: List<Item>, k: BrowseKey): List<Item> {
    val minR = RATING_VALUES[k.minRi]
    val f = ofSource(items, k.source).filter { i ->
        val m = MetaRepo.cached(i)
        val r = m?.score() ?: 0.0
        r >= minR &&
            (k.year == "Alle" || yearMatches(k.year, yearOf(i))) &&
            (k.genre == null || (m != null && Genres.categoriesOf(m).contains(k.genre))) &&
            (k.country == null || (m != null && m.countries.contains(k.country)))
    }
    return when (k.sort) {
        0 -> f.asReversed()
        1 -> f.sortedByCached(true) { MetaRepo.cached(it)?.votes ?: -1 }
        2 -> f.sortedByCached(true) { MetaRepo.cached(it)?.score() ?: -1.0 }
        3 -> f.sortedByCached(false) { MetaRepo.cached(it)?.score() ?: 99.0 }
        4 -> f.sortedByCached(true) { yearOf(it) }
        5 -> f.sortedByCached(false) { if (yearOf(it) == 0) 9999 else yearOf(it) }
        6 -> f.sortedByCached(false) { it.title.lowercase() }
        7 -> f.sortedByCached(true) { it.title.lowercase() }
        else -> f
    }
}

@Composable
fun BrowseScreen(
    state: BrowseState, gridState: LazyGridState, tab: Int,
    onOpen: (Item) -> Unit, onBack: () -> Unit, onGoTab: (Int) -> Unit,
    all: List<Item> = emptyList(), onPlay: (Item, String, String, Long) -> Unit = { _, _, _, _ -> },
    onPerson: (PersonRef) -> Unit = {}
) {
    val ctx = LocalContext.current
    var browseQuery by remember { mutableStateOf("") }
    val browseVoice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?.takeIf { it.isNotBlank() }?.let { browseQuery = it }
    }
    BrowsePrefs.init(ctx)
    val req = state.req
    val ver = state.ver
    val newer = Classifier.version != ver
    val cols = AppSettings.gridCols
    val rows = AppSettings.gridRows
    val sideFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    val restoreFocus = remember { FocusRequester() }
var restoreIdx by remember { mutableIntStateOf(state.focused) }
    // Runde 18: „Neue Infos · aktualisieren“ lädt die Liste neu und setzt den Fokus auf den ersten Titel (statt in die Seitenleiste)
    var jumpFirst by remember { mutableStateOf(false) }
    var jumpTick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var inSidebar by remember { mutableStateOf(false) }
    var inFilters by remember { mutableStateOf(false) }

    BackHandler(enabled = inSidebar || inFilters) { onBack() }
    BackHandler(enabled = !inFilters && !inSidebar) { runCatching { filterFocus.requestFocus() } }

    // Filter-Auswahl und Ergebnisliste werden im Hintergrund berechnet ("Alle Filme" kann 60.000 Titel haben)
    val facets by produceState(state.lastFacets?.takeIf { it.first == ver }?.second, req, ver) {
        value = withContext(Dispatchers.Default) { facetsOf(req.items) }.also { state.lastFacets = ver to it }
    }
    val genres = facets?.genres.orEmpty()
    val countries = facets?.countries.orEmpty()
    val yearOptions = listOf("Alle") + DECADES + facets?.years.orEmpty()

    val key = BrowseKey(state.sort, state.minRi, state.year, state.genre, state.country, ver, state.source)
    val listOrNull by produceState(state.lastList?.takeIf { state.lastKey == key }, req, key) {
        value = withContext(Dispatchers.Default) { filterAndSort(req.items, key) }.also { state.lastKey = key; state.lastList = it }
    }
    val unfilteredList = listOrNull.orEmpty()
    val list = remember(unfilteredList, browseQuery) {
        if (browseQuery.isBlank()) unfilteredList
        else unfilteredList.filter { item ->
            item.title.contains(browseQuery, true) || item.group.contains(browseQuery, true)
        }
    }
    val ready = listOrNull != null

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        LastFocus.pending = null
        if (BrowsePrefs.detail) {
            listState.scrollToItem(restoreIdx)
            withFrameNanos { }
            withFrameNanos { }
        } else delay(200)
        runCatching { restoreFocus.requestFocus() }
    }

    LaunchedEffect(jumpFirst) {
        if (!jumpFirst) return@LaunchedEffect
        // warten, bis die neu sortierte Liste fertig ist (höchstens 5 s), dann nach ganz oben und den ersten Titel markieren
        var w = 0
        while (state.lastKey != key && w < 5000) { delay(50); w += 50 }
        if (BrowsePrefs.detail) { listState.scrollToItem(0) } else { runCatching { gridState.scrollToItem(0) } }
        withFrameNanos { }
        withFrameNanos { }
        delay(150)
        runCatching { restoreFocus.requestFocus() }
        jumpFirst = false
    }

    Box(
        Modifier.fillMaxSize().background(Bg)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { runCatching { filterFocus.requestFocus() }; true } else false
            }
    ) {
        Column(Modifier.fillMaxSize().sidebarPad().padding(horizontal = 32.dp, vertical = DS.PageY)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(req.title, color = Color.White, fontSize = spx(34), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(if (ready) "${list.size} Titel" else "Lade …", color = Muted, fontSize = 16.sp)
                // Neue Infos aus der Genre-Erkennung: erst auf Wunsch neu sortieren, damit sich nichts unter dem Cursor verschiebt
                if (newer && ready) SmallPill("Neue Infos · aktualisieren") {
                    state.ver = Classifier.version; state.focused = 0; restoreIdx = 0; jumpTick++; jumpFirst = true
                }
                Spacer(Modifier.weight(1f))
                // Position und Hinweis sitzen jetzt dezent in der Kopfzeile statt über den Titeln
                if (list.isNotEmpty()) {
                    Text("Zurück = Filter", color = Muted, fontSize = 13.sp)
                    Text("${minOf(state.focused + 1, list.size)} / ${list.size}", color = Color.White, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(Surf2).padding(horizontal = 14.dp, vertical = 5.dp))
                }
                // Platz für die Genre-Anzeige oben rechts, damit sie nichts verdeckt
                if (Classifier.running) Spacer(Modifier.width(MetaBadgeWidth - 10.dp))
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.onFocusChanged { inFilters = it.hasFocus }) {
                // Innenabstand: der vergrößerte markierte Knopf wird sonst links/rechts/oben abgeschnitten
                LazyRow(
                    Modifier.offset(x = (-14).dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    item {
                        DropdownPill("Sortierung", SORTS[state.sort], SORTS, state.sort,
                            active = state.sort != req.sort, modifier = Modifier.focusRequester(filterFocus), icon = FIcon.Sort) { state.sort = it }
                    }
                    item {
                        DropdownPill("Rating", RATING_LABELS[state.minRi], RATING_LABELS, state.minRi, active = state.minRi != 0, icon = FIcon.Rating) { state.minRi = it }
                    }
                    item {
                        DropdownPill("Jahr", state.year, yearOptions, yearOptions.indexOf(state.year).coerceAtLeast(0), active = state.year != "Alle", icon = FIcon.Year) { state.year = yearOptions[it] }
                    }
                    if (genres.isNotEmpty()) item {
                        val go = listOf("Alle") + genres
                        DropdownPill("Genre", state.genre ?: "Alle", go, go.indexOf(state.genre).coerceAtLeast(0), active = state.genre != null, icon = FIcon.Genre) {
                            state.genre = if (it == 0) null else go[it]
                        }
                    }
                    if (countries.isNotEmpty()) item {
                        val co = listOf("Alle") + countries.map { it.second }
                        val idx = countries.indexOfFirst { it.first == state.country } + 1
                        DropdownPill("Land", countries.firstOrNull { it.first == state.country }?.second ?: "Alle", co, idx, active = state.country != null, icon = FIcon.Country) {
                            state.country = if (it == 0) null else countries[it - 1].first
                        }
                    }
                    // Mehrere Playlists gleichzeitig: nach Quelle filtern
                    val srcNames = PlaylistStore.sourceNames
                    if (srcNames.size > 1) item {
                        val so = listOf("Alle") + srcNames
                        DropdownPill("Quelle", if (state.source < 0) "Alle" else srcNames.getOrElse(state.source) { "Alle" }, so, state.source + 1,
                            active = state.source >= 0, icon = FIcon.Grid) { state.source = it - 1 }
                    }
                    item {
                        FilterPill("Zurücksetzen", false, icon = FIcon.Reset) {
                            state.minRi = 0; state.year = "Alle"; state.genre = null; state.country = null; state.source = -1; state.sort = req.sort.coerceIn(0, 8)
                        }
                    }
                    // Ansicht und Raster: selten gebraucht, deshalb ganz am Ende
                    item {
                        val views = listOf("Raster", "Kino")
                        DropdownPill("Ansicht", views[if (BrowsePrefs.detail) 1 else 0], views, if (BrowsePrefs.detail) 1 else 0, icon = FIcon.View) { BrowsePrefs.set(ctx, it == 1) }
                    }
                    item {
                        // Titel pro Zeile: gilt nur hier (Raster und Kino) und in der Kategorien-Liste, nicht auf der Startseite
                        // Raster und Kino haben je eine eigene Einstellung (Kino: Covern bleiben immer ganz sichtbar, höchstens kleiner)
                        val kino = BrowsePrefs.detail
                        val pr = if (kino) listOf(0, 5, 6, 7, 8, 10, 12) else listOf(0, 4, 5, 6, 8, 10, 12)
                        val prl = pr.map { if (it == 0) "Auto" else "$it" }
                        val curV = if (kino) General.kinoPerRow else General.perRow
                        val ci = pr.indexOf(curV).coerceAtLeast(0)
                        DropdownPill(if (kino) "Titel pro Zeile (Kino)" else "Titel pro Zeile", prl[ci], prl, ci, icon = FIcon.View) {
                            if (kino) General.setKinoPerRow(ctx, pr[it]) else General.setPerRow(ctx, pr[it])
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Field(
                    browseQuery,
                    { browseQuery = it },
                    "Titel oder Kategorie suchen…",
                    Modifier.weight(1f)
                )
                PillButton("Voice", icon = AIcon.Mic) {
                    val launched = runCatching {
                        browseVoice.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie, series or category")
                        )
                    }.isSuccess
                    if (!launched) {
                        runCatching { browseVoice.launch(Intent(RecognizerIntent.ACTION_WEB_SEARCH)) }
                    }
                }
                if (browseQuery.isNotBlank()) PillButton("Clear") { browseQuery = "" }
            }
            Spacer(Modifier.height(2.dp))
            BoxWithConstraints(Modifier.fillMaxSize()) {
                if (ready && list.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(AIcon.Sliders, "Keine Treffer", "Mit diesen Filtern gibt es hier keine Titel.", action = "Filter zurücksetzen") {
                        runCatching { filterFocus.requestFocus() }
                        state.minRi = 0; state.year = "Alle"; state.genre = null; state.country = null; state.source = -1; state.sort = req.sort.coerceIn(0, 8)
                    }
                }
                if (!ready) {
                    // Lade-Skelett in Form des Rasters, bis die Liste im Hintergrund berechnet ist
                    val gap = 14.dp
                    val w = (maxWidth - gap * (cols - 1)) / cols
                    val h = ((maxHeight - 12.dp - gap * (rows - 1)) / rows - 42.dp).coerceAtLeast(60.dp)
                    val sk = shimmerColor()
                    Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.padding(vertical = 6.dp)) {
                        repeat(rows) {
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                repeat(cols) { SkeletonBox(Modifier.width(w).height(h), sk, RoundedCornerShape(DS.RadiusM)) }
                            }
                        }
                    }
                } else if (BrowsePrefs.detail) {
                    CinemaView(list, state, listState, restoreIdx, restoreFocus, all, onPlay, onPerson, onOpen, jumpTick)
                } else {
                    // Runde 15: wie auf der Startseite große Cover, bei längerem Markieren klappt die Karte zum Szenenbild mit
                    // Infos auf (über die Nachbarn; in den rechten Spalten nach links). Spaltenzahl folgt „Ansicht“ oben.
                    val g = cardGrid(maxWidth, perRow = General.perRow)
                    val land = General.cardArt == 1
                    val ncols = if (land) intArrayOf(8, 6, 5)[General.cardSize.coerceIn(0, 2)] else if (General.perRow > 0) General.perRow else intArrayOf(10, 8, 6)[General.cardSize.coerceIn(0, 2)]
                    val gap = if (land) g.gap else g.posterGap
                    val cardW = (maxWidth - gap * (ncols - 1)) / ncols
                    Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(ncols),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalArrangement = Arrangement.spacedBy(dpx(18)),
                        contentPadding = PaddingValues(top = dpx(14), bottom = dpx(60)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(list) { idx, item ->
                            val mod = Modifier
                                .onFocusChanged { st -> if (st.hasFocus) state.focused = idx }
                                .then(if (idx == restoreIdx) Modifier.focusRequester(restoreFocus) else Modifier)
                            when {
                                item.kind == Kind.LIVE -> PosterCard(item, { onOpen(item) }, Modifier.width(cardW).then(mod), fixedPosterHeight = cardW * 1.5f - 42.dp)
                                land -> LandscapeCard(item, Modifier.width(cardW).then(mod)) { onOpen(item) }
                                else -> RowPosterCard(item, cardW, mod, overlay = if (idx % ncols >= ncols / 2) 2 else 1) { onOpen(item) }
                            }
                        }
                    }
                    }
                }
            }
        }
        Sidebar(tab, onGoTab, Modifier.align(SidebarPrefs.align()), sideFocus) { inSidebar = it }
    }
}

/**
 * Kino-Ansicht (Runde 16): oben die Filter (Kopfzeile), darunter EINE Reihe mit großen Covern wie auf der Startseite.
 * Die Markierung bleibt in der Mitte, die Reihe gleitet durch; bleibt ein Cover markiert, klappt es zum Szenenbild mit Infos auf.
 */
@Composable
private fun CinemaView(
    list: List<Item>, state: BrowseState, rowState: androidx.compose.foundation.lazy.LazyListState,
    restoreIdx: Int, restoreFocus: FocusRequester,
    all: List<Item>, onPlay: (Item, String, String, Long) -> Unit, onPerson: (PersonRef) -> Unit, onOpen: (Item) -> Unit,
    jumpTick: Int = 0
) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        // Oben die Titelreihe, darunter der Detail-Screen (derselbe wie auf der Titelseite). Runter schiebt ihn nach oben,
        // die Titel verschwinden nach oben; Hoch am Kopf schiebt sie zurück.
        // Cover immer ganz sichtbar (2:3, nie beschnitten): die Breite ergibt sich aus „Titel pro Zeile (Kino)“, die Höhe ist
        // nach oben begrenzt (40 % des Bildes), damit unter der Reihe viel Platz für Titel, Wertungen und Beschreibung bleibt.
        val n = if (General.kinoPerRow > 0) General.kinoPerRow else 8
        val byWidth = (maxWidth - RowPadX * 2 - dpx(20) * (n - 1)) / n
        val posterH = minOf(byWidth * 1.5f, maxHeight * 0.40f).coerceAtLeast(dpx(160))
        val baseW = posterH / 1.5f
        val aspect = 1.5f
        val rowH = posterH + dpx(56)
        val peekY = rowH - dpx(24)                       // Detail-Screen ragt so weit unter die Reihe
        var cur by remember(jumpTick) { mutableIntStateOf(state.focused.coerceIn(0, maxOf(list.lastIndex, 0))) }
        var open by remember { mutableStateOf(false) }
        val t by animateFloatAsState(
            if (open) 1f else 0f,
            if (Look.motion == 0) snap() else tween(if (Look.motion == 1) 300 else 480, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "cin"
        )
        val scope = rememberCoroutineScope()
        // Detail erst nach kurzem Verweilen laden (beim schnellen Durchblättern wird nichts geladen)
        var shown by remember { mutableStateOf<Item?>(null) }
        LaunchedEffect(cur, list) {
            if (!open) { delay(380); shown = list.getOrNull(cur) }
        }
        val posterReq = restoreFocus
        BackHandler(enabled = open) { open = false; scope.launch { delay(60); runCatching { posterReq.requestFocus() } } }
        shown?.let { it0 ->
            key(it0.id) {
                Box(Modifier.fillMaxSize().offset { androidx.compose.ui.unit.IntOffset(0, (peekY.toPx() * (1f - t)).toInt()) }
                    .onFocusChanged { if (it.hasFocus && !open) open = true }) {
                    DetailScreen(
                        it0, all, onPlay = { url, ti, pos -> onPlay(it0, ti, url, pos) },
                        onPerson = onPerson, onOpenItem = onOpen, onBack = {},
                        embedded = true, autoFocus = open, showActions = open,
                        onTopUp = { open = false; scope.launch { delay(60); runCatching { posterReq.requestFocus() } } }
                    )
                }
            }
        }
        // Hinweis: hier geht es mit Runter weiter
        if (t < 0.99f) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = dpx(28), bottom = dpx(18)).graphicsLayer { alpha = 1f - t }
                .clip(RoundedCornerShape(dpx(20))).background(Tok.Glass).padding(horizontal = dpx(16), vertical = dpx(6)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))
        ) {
            IconLabel(AIcon.Down, "Runter für Details und Abspielen", Muted, 14.sp, iconSize = 16.dp, maxLines = 1)
        }
        // Titelreihe (liegt über dem Detail-Kopf), schiebt sich beim Öffnen nach oben aus dem Bild
        Box(Modifier.fillMaxWidth().height(rowH).offset { androidx.compose.ui.unit.IntOffset(0, -(rowH.toPx() * t).toInt()) }.background(Bg)) {
            CenterFollow(rowState, cur)
            Box(Modifier.fillMaxSize()) {
            androidx.compose.foundation.lazy.LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(dpx(20)),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = RowPadX, vertical = dpx(20)),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(list) { idx, item ->
                    val mod = Modifier
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.DirectionDown) {
                                shown = item; open = true; true
                            } else false
                        }
                        .onFocusChanged { st -> if (st.hasFocus) { cur = idx; state.focused = idx; if (open) open = false } }
                        .then(if (idx == cur) Modifier.focusRequester(posterReq) else Modifier)
                    RowPosterCard(item, baseW, mod, overlay = 0, aspect = aspect, expand = false) {
                        // OK: Detail hochschieben, Fokus auf "Abspielen" (ein weiterer OK startet)
                        shown = item; open = true
                    }
                }
            }
            }
        }
    }
}
