package com.bandmr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bandmr.app.data.AppDesign
import com.bandmr.app.ui.theme.LocalAppDesign

@Composable
fun LibraryHeading(songCount: Int) {
    val design = LocalAppDesign.current
    when (design) {
        AppDesign.MONO -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("THE PRACTICE COLLECTION", style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("연습할 곡들.", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
                Text("%02d".format(songCount), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("한 파트는 비워 두고, 나의 연주로 채우기.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        }
        AppDesign.AMP -> StudioPanel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                Text("BAND MR / SESSION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("오늘도,\n한 테이크.", style = MaterialTheme.typography.headlineLarge)
                    Text("내 사운드에 집중하는 시간", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TrackArtwork(seed = 0, size = 88.dp)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("LIBRARY   /   %02d TRACKS".format(songCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppDesign.BLUE -> Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("PLAY YOUR PART", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("좋아하는 곡에\n나의 연주를.", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    TrackArtwork(seed = 0, size = 72.dp)
                }
                Text("연습할 곡 $songCount 개가 기다리고 있어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        AppDesign.STUDIO -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("나의 연습실", style = MaterialTheme.typography.headlineLarge)
            Text("좋아하는 곡을, 나만의 반주로.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        AppDesign.SNOW, AppDesign.INK, AppDesign.HONG, AppDesign.MOSS -> Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "연습할 곡",
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineLarge,
                color = if (design == AppDesign.MOSS) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            Text(
                songCount.toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PlayerHeading(songId: Long, title: String, subtitle: String) {
    when (LocalAppDesign.current) {
        AppDesign.MONO -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("NOW IN SESSION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrackArtwork(seed = songId, size = 44.dp)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AppDesign.AMP -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("SESSION / %02d".format(songId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TrackArtwork(seed = songId, size = 76.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AppDesign.BLUE -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TrackArtwork(seed = songId, size = 144.dp)
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppDesign.STUDIO -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TrackArtwork(seed = songId, size = 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("지금 연습할 곡", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AppDesign.SNOW, AppDesign.INK, AppDesign.HONG, AppDesign.MOSS -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TrackArtwork(seed = songId, size = 64.dp, label = title)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
