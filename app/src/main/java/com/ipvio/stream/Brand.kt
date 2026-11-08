package com.ipvio.stream

import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable

/**
 * Das IPVIO-Zeichen (Play-Symbol mit Farbverlauf) als Bild: res/drawable/ic_brand_mark.png.
 * Der Schriftzug "IPVIO" wird daneben als weißer Text gesetzt.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_brand_mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}
