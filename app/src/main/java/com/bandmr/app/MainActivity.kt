package com.bandmr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bandmr.app.data.AppDesign
import com.bandmr.app.ui.components.BrandMark
import com.bandmr.app.ui.components.DesignPicker
import com.bandmr.app.ui.library.LibraryScreen
import com.bandmr.app.ui.player.PlayerScreen
import com.bandmr.app.ui.settings.SettingsScreen
import com.bandmr.app.ui.theme.BandMrTheme
import com.bandmr.app.ui.theme.LocalAppDesign
import com.bandmr.app.ui.theme.designIsDark
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val design by Locator.settings.design.collectAsState(initial = AppDesign.MONO)
            val dark = designIsDark(design, isSystemInDarkTheme())
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            BandMrTheme(design) {
                BandMrNav()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandMrNav() {
    val design = LocalAppDesign.current
    val scope = rememberCoroutineScope()
    var showDesigns by remember { mutableStateOf(false) }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "library"

    val title = when {
        route.startsWith("player/") -> "연습 스튜디오"
        route == "settings" -> "설정"
        else -> "밴드 MR"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (route == "library") BrandMark()
                        Text(title, style = MaterialTheme.typography.titleMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (route != "library") {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { showDesigns = true }) { Text("디자인") }
                    if (route != "settings") {
                        IconButton(onClick = { nav.navigate("settings") }) {
                            Icon(Icons.Default.Settings, contentDescription = "설정")
                        }
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "library",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable("library") {
                LibraryScreen(onOpenSong = { id -> nav.navigate("player/$id") })
            }
            composable(
                "player/{songId}",
                arguments = listOf(navArgument("songId") { type = NavType.LongType }),
            ) { entry ->
                val songId = entry.arguments?.getLong("songId") ?: 0L
                PlayerScreen(songId = songId)
            }
            composable("settings") {
                SettingsScreen(onChooseDesign = { showDesigns = true })
            }
        }
    }
    if (showDesigns) {
        DesignPicker(
            selectedDesign = design,
            onSelect = { selected ->
                scope.launch { Locator.settings.setDesign(selected) }
                showDesigns = false
            },
            onDismiss = { showDesigns = false },
        )
    }
}
