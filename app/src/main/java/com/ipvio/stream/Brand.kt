package com.ipvio.stream

import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable

/**
 * Das Infinite Streams-Zeichen als Bild: res/drawable/ic_brand_mark.png.
 * Das Infinite Streams Media Logo wird an dieser Stelle verwendet.
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
