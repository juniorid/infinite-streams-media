package com.ipvio.stream

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Runde 22: Einstellungen „Ansicht“ in Unterseiten mit je einer Live-Vorschau.
 * Jede Unterseite zeigt ein Mini-Bild der App. Beim Markieren einer Auswahl (ohne OK) zeigt die Vorschau sofort,
 * wie es aussehen würde; Schritt-Knöpfe und Schalter wirken ohnehin direkt.
 */
private val PREVIEW_W = 400.dp
private val PREVIEW_H = 225.dp

@Composable
internal fun ViewPage(ctx: Context) {
    CatPrefs.init(ctx); BrowsePrefs.init(ctx)
    val tabs = listOf("Karten", "Seitenleiste", "Raster & Ansicht", "Startseite", "Kategorien")
    var tab by remember { mutableIntStateOf(0) }
    // Beim Markieren eines Reiters wechselt die Unterseite sofort: Links/Rechts blättert durch alle Bereiche
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tabs.forEachIndexed { i, l -> ViewChip(l, tab == i, { on -> if (on) tab = i }) { tab = i } }
    }
    when (tab) {
        0 -> CardsTab(ctx)
        1 -> SidebarTab(ctx)
        2 -> GridTab(ctx)
        3 -> HomeTab(ctx)
        else -> CategoriesTab(ctx)
    }
}

/** Links die Einstellungen, rechts die feste Vorschau. */
@Composable
private fun TabLayout(preview: @Composable () -> Unit, controls: @Composable ColumnScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = controls)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            preview()
            Text("Vorschau", color = Muted, fontSize = 13.sp)
        }
    }
}

/** Auswahl-Chips: Markieren zeigt die Vorschau (hover), OK übernimmt (onPick). */
@Composable
private fun Chips(labels: List<String>, selected: Int, hov: MutableState<Int?>, onPick: (Int) -> Unit) {
    val items: @Composable () -> Unit = {
        labels.forEachIndexed { i, l ->
            ViewChip(l, selected == i, { on -> if (on) hov.value = i else if (hov.value == i) hov.value = null }) { onPick(i) }
        }
    }
    if (labels.sumOf { it.length } > 26) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { items() }
    else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { items() }
}

// ───────── Bausteine der Mini-Vorschau ─────────

@Composable
private fun rememberImgs(): List<Pair<Any?, Any?>> = remember(PlaylistStore.channels) {
    PlaylistStore.channels.asSequence().filter { it.kind != Kind.LIVE && !it.logo.isNullOrBlank() }.take(30).map {
        val m = MetaRepo.cached(it)
        (m?.poster ?: it.logo) to (m?.backdrop ?: it.logo)
    }.toList()
}

@Composable
private fun Tile(img: Any?, w: Dp, h: Dp, modifier: Modifier = Modifier, radius: Dp = 4.dp) {
    Box(modifier.width(w).height(h).clip(RoundedCornerShape(radius)).background(Color(0x26FFFFFF))) {
        if (img != null) coil.compose.AsyncImage(model = img, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun Img(img: Any?, modifier: Modifier = Modifier) {
    if (img != null) coil.compose.AsyncImage(model = img, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = modifier)
}

@Composable
private fun Bar(w: Dp, h: Dp = 4.dp, color: Color = Color(0x55FFFFFF)) {
    Box(Modifier.width(w).height(h).clip(RoundedCornerShape(h / 2)).background(color))
}

@Composable
private fun MiniNavItem(sel: Boolean, labels: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (sel) Accent2 else Color(0x66FFFFFF)))
        if (labels) Bar(if (sel) 24.dp else 18.dp, 3.dp, if (sel) Accent2 else Color(0x44FFFFFF))
    }
}

/** Mini-Fernseher mit Seitenleiste an der gewählten Position; der Inhalt bekommt den freien Platz. */
@Composable
private fun MiniScreen(pos: Int, labels: Boolean, content: @Composable (Dp, Dp) -> Unit) {
    val sbW = if (labels) 58.dp else 22.dp
    val topH = 20.dp
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.width(PREVIEW_W).height(PREVIEW_H).clip(shape).background(Color(0xFF0B0E17)).border(1.dp, Color(0x33FFFFFF), shape)) {
        val ps = if (pos == SidebarPrefs.LEFT) sbW else 0.dp
        val pe = if (pos == SidebarPrefs.RIGHT) sbW else 0.dp
        val pt = if (pos == SidebarPrefs.TOP) topH else 0.dp
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = ps, end = pe, top = pt).padding(8.dp)) { content(maxWidth, maxHeight) }
        if (pos == SidebarPrefs.TOP) {
            Row(
                Modifier.fillMaxWidth().height(topH).background(Color(0x14FFFFFF)).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
            ) { repeat(6) { MiniNavItem(it == 1, labels) } }
        } else {
            Column(
                Modifier.align(if (pos == SidebarPrefs.RIGHT) Alignment.CenterEnd else Alignment.CenterStart)
                    .width(sbW).fillMaxHeight().background(Color(0x14FFFFFF)).padding(horizontal = 6.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) { repeat(6) { MiniNavItem(it == 1, labels) } }
        }
    }
}

// ───────── 1. Karten ─────────

@Composable
private fun CardsTab(ctx: Context) {
    val hSize = remember { mutableStateOf<Int?>(null) }
    val hArt = remember { mutableStateOf<Int?>(null) }
    val hStyle = remember { mutableStateOf<Int?>(null) }
    val size = hSize.value ?: General.cardSize
    val art = hArt.value ?: General.cardArt
    val style = hStyle.value ?: General.previewStyle
    val imgs = rememberImgs()
    TabLayout(preview = {
        MiniScreen(SidebarPrefs.pos, SidebarPrefs.labels) { maxWidth, maxHeight ->
            val land = art == 1
            val n = if (land) intArrayOf(5, 4, 3)[size.coerceIn(0, 2)] else intArrayOf(7, 6, 4)[size.coerceIn(0, 2)]
            val gap = 5.dp
            val w = (maxWidth - gap * (n - 1)) / n
            val h = if (land) w * 9 / 16 else w * 3 / 2
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Bar(54.dp, 5.dp, Color(0x99FFFFFF))
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (i in 0 until n) Tile(imgs.getOrNull(i)?.let { if (land) it.second else it.first }, w, h)
                }
                // Markierte Karte: so sieht die Vorschau beim Markieren aus
                val ph = maxHeight - h - 28.dp
                if (ph > 30.dp) {
                    Row(Modifier.fillMaxWidth().height(ph).clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (style == 0) {
                            Tile(imgs.getOrNull(1)?.first, ph * 2 / 3 - 4.dp, ph - 12.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Bar(70.dp, 5.dp, Color(0xCCFFFFFF)); Bar(46.dp); Bar(90.dp); Bar(80.dp)
                            }
                        } else {
                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))) {
                                Img(imgs.getOrNull(1)?.second, Modifier.fillMaxSize())
                                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent))))
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Bar(70.dp, 5.dp, Color(0xCCFFFFFF)); Bar(46.dp); Bar(60.dp) }
                            }
                        }
                    }
                }
            }
        }
    }) {
        Text("Kartengröße", color = Muted, fontSize = 15.sp)
        Chips(listOf("Klein", "Normal", "Groß"), General.cardSize, hSize) { General.setCardView(ctx, it, General.cardArt) }
        Text("Bilder bei Filmen und Serien", color = Muted, fontSize = 15.sp)
        Chips(listOf("Cover", "Szenenbild"), General.cardArt, hArt) { General.setCardView(ctx, General.cardSize, it) }
        Text("Vorschau beim Markieren", color = Muted, fontSize = 15.sp)
        Chips(listOf("Infos auf dem Poster", "Breites Szenenbild"), General.previewStyle, hStyle) { General.setPreviewStyle(ctx, it) }
        Hint("Beim Markieren einer Auswahl zeigt die Vorschau sofort die Wirkung, OK übernimmt sie. Schriftgröße und Farben findest du unter „Design“.")
    }
}

// ───────── 2. Seitenleiste ─────────

@Composable
private fun SidebarTab(ctx: Context) {
    val hPos = remember { mutableStateOf<Int?>(null) }
    val pos = hPos.value ?: SidebarPrefs.pos
    val imgs = rememberImgs()
    TabLayout(preview = {
        MiniScreen(pos, SidebarPrefs.labels) { maxWidth, maxHeight ->
            val gap = 5.dp
            val cols = 4
            val w = (maxWidth - gap * (cols - 1)) / cols
            val h = minOf(w * 3 / 2, (maxHeight - gap - 14.dp) / 2)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Bar(54.dp, 5.dp, Color(0x99FFFFFF))
                repeat(2) { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (c in 0 until cols) Tile(imgs.getOrNull(r * cols + c)?.first, w, h)
                    }
                }
            }
        }
    }) {
        Text("Position", color = Muted, fontSize = 15.sp)
        Chips(SidebarPrefs.POS_NAMES, SidebarPrefs.pos, hPos) { SidebarPrefs.set(ctx, pos = it) }
        ToggleRow("Immer mit Text", "Aus: nur Symbole, die Texte erscheinen beim Hineingehen. An: Symbole und Texte sind immer zu sehen (braucht mehr Platz).", SidebarPrefs.labels) {
            SidebarPrefs.set(ctx, labels = it)
        }
        Hint("Zurück bringt dich jederzeit in die Seitenleiste – auch wenn sie rechts oder oben steht.")
    }
}

// ───────── 3. Raster & Ansicht ─────────

@Composable
private fun GridTab(ctx: Context) {
    val hView = remember { mutableStateOf<Int?>(null) }
    val view = hView.value ?: if (BrowsePrefs.detail) 1 else 0
    val cols = AppSettings.gridCols
    val rows = AppSettings.gridRows
    val land = General.cardArt == 1
    val imgs = rememberImgs()
    TabLayout(preview = {
        MiniScreen(SidebarPrefs.pos, SidebarPrefs.labels) { maxWidth, maxHeight ->
            val gap = 4.dp
            if (view == 0) {
                // Raster: Spalten × Zeilen
                val w = (maxWidth - gap * (cols - 1)) / cols
                val h = minOf(if (land) w * 9 / 16 else w * 3 / 2, (maxHeight - gap * (rows - 1)) / rows)
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (r in 0 until rows) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (c in 0 until cols) Tile(imgs.getOrNull(r * cols + c)?.let { if (land) it.second else it.first }, w, h, radius = 3.dp)
                    }
                }
            } else {
                // Kino: oben Details zum markierten Titel, unten eine Reihe zum Durchblättern
                val n = 6
                val w = (maxWidth - gap * (n - 1)) / n
                val h = if (land) w * 9 / 16 else w * 3 / 2
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().height(maxHeight - h - 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(Color(0x14FFFFFF))) {
                            Img(imgs.getOrNull(1)?.second, Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent))))
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Bar(80.dp, 6.dp, Color(0xEEFFFFFF)); Bar(50.dp); Bar(100.dp); Bar(90.dp)
                                Box(Modifier.padding(top = 4.dp).width(36.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xDDFFFFFF)))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (i in 0 until n) Tile(imgs.getOrNull(i + 2)?.let { if (land) it.second else it.first }, w, h, radius = 3.dp)
                    }
                }
            }
        }
    }) {
        Text("Standard-Ansicht", color = Muted, fontSize = 15.sp)
        Chips(listOf("Raster", "Kino"), if (BrowsePrefs.detail) 1 else 0, hView) { BrowsePrefs.set(ctx, it == 1) }
        Hint("Raster zeigt viele Titel auf einmal. Kino zeigt oben die Details zum markierten Titel und unten eine Reihe zum Durchblättern.")
        StepRow("Spalten (Raster)", cols.toString(),
            { AppSettings.setGrid(ctx, cols - 1, rows) },
            { AppSettings.setGrid(ctx, cols + 1, rows) })
        StepRow("Zeilen (Raster)", rows.toString(),
            { AppSettings.setGrid(ctx, cols, rows - 1) },
            { AppSettings.setGrid(ctx, cols, rows + 1) })
        Hint("Das Raster kannst du zusätzlich direkt in der Rasteransicht mit dem Raster-Knopf ändern.")
    }
}

// ───────── 4. Startseite ─────────

@Composable
private fun HomeTab(ctx: Context) {
    val heroOpts = listOf(0, 6, 9, 15)
    val hHero = remember { mutableStateOf<Int?>(null) }
    val sec = hHero.value?.let { heroOpts[it] } ?: General.heroSec
    val imgs = rememberImgs()
    TabLayout(preview = {
        MiniScreen(SidebarPrefs.pos, SidebarPrefs.labels) { maxWidth, maxHeight ->
            val scale = maxWidth.value / 900f
            val cw = (AppSettings.cardW * scale).dp
            val ch = cw * 3 / 2
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Banner mit Fortschrittsbalken: wechselt im gewählten Takt
                Box(Modifier.fillMaxWidth().height(maxHeight * 0.48f).clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF))) {
                    Img(imgs.getOrNull(0)?.second, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xDD000000), Color.Transparent))))
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Bar(86.dp, 7.dp, Color(0xEEFFFFFF)); Bar(54.dp); Bar(110.dp)
                    }
                    if (sec > 0) {
                        val t = rememberInfiniteTransition(label = "hero")
                        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(sec * 1000, easing = LinearEasing), RepeatMode.Restart), label = "p")
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(p).height(3.dp).background(Accent2))
                    }
                }
                // Reihen mit Karten in der gewählten Kartengröße
                Bar(46.dp, 5.dp, Color(0x99FFFFFF))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    var used = 0.dp
                    var i = 0
                    while (used + cw <= maxWidth && i < 12) {
                        Tile(imgs.getOrNull(i + 3)?.first, cw, minOf(ch, maxHeight * 0.34f), radius = 3.dp)
                        used += cw + 5.dp; i++
                    }
                }
            }
        }
    }) {
        Text("Banner wechselt", color = Muted, fontSize = 15.sp)
        Chips(heroOpts.map { if (it == 0) "nie" else "alle $it s" }, heroOpts.indexOf(General.heroSec).coerceAtLeast(0), hHero) { General.setHeroSec(ctx, heroOpts[it]) }
        StepRow("Kartengröße (Reihen)", AppSettings.cardW.toString(),
            { AppSettings.setCardW(ctx, AppSettings.cardW - 10) },
            { AppSettings.setCardW(ctx, AppSettings.cardW + 10) })
        Hint("Beim Markieren einer Auswahl zeigt die Vorschau sofort die Wirkung, OK übernimmt sie.")
    }
}

// ───────── 5. Kategorien ─────────

@Composable
private fun CategoriesTab(ctx: Context) {
    val hView = remember { mutableStateOf<Int?>(null) }
    val list = (hView.value ?: if (CatPrefs.listView) 0 else 1) == 0
    val imgs = rememberImgs()
    TabLayout(preview = {
        MiniScreen(SidebarPrefs.pos, SidebarPrefs.labels) { maxWidth, maxHeight ->
            if (list) {
                // Liste links, Vorschau der markierten Kategorie rechts
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width(maxWidth * 0.34f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(8) { i ->
                            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)).background(if (i == 2) Color(0xDDFFFFFF) else Color(0x1FFFFFFF)), contentAlignment = Alignment.CenterStart) {
                                Bar(if (i == 2) 50.dp else 40.dp + (i * 5).dp, 3.dp, if (i == 2) Color(0xFF222222) else Color(0x66FFFFFF))
                            }
                        }
                    }
                    val gap = 4.dp
                    val w = (maxWidth * 0.66f - 10.dp - gap * 2) / 3
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(2) { r -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) { for (c in 0 until 3) Tile(imgs.getOrNull(r * 3 + c)?.first, w, minOf(w * 3 / 2, (maxHeight - gap) / 2), radius = 3.dp) } }
                    }
                }
            } else {
                val gap = 6.dp
                val w = (maxWidth - gap * 3) / 4
                val h = (maxHeight - gap * 2) / 3
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(3) { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (c in 0 until 4) {
                                val hue = ((r * 4 + c) * 31f) % 360f
                                Box(Modifier.width(w).height(h).clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.38f), Color.hsv(hue, 0.7f, 0.16f)))), contentAlignment = Alignment.BottomStart) {
                                    Box(Modifier.padding(5.dp)) { Bar(w / 2, 4.dp, Color(0xBBFFFFFF)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }) {
        val cv = listOf("Liste mit Vorschau", "Kacheln")
        Text("Ansicht", color = Muted, fontSize = 15.sp)
        Chips(cv, if (CatPrefs.listView) 0 else 1, hView) { CatPrefs.set(ctx, it == 0) }
        Hint("Beim Markieren einer Auswahl zeigt die Vorschau sofort die Wirkung, OK übernimmt sie.")
    }
}
