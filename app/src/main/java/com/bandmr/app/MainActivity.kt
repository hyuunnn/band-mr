package com.bandmr.app

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
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
import com.bandmr.app.ui.theme.designColors
import com.bandmr.app.ui.theme.designIsDark
import com.bandmr.app.ui.theme.startingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 시작 화면은 프로세스가 뜨기 전에 그려진다. 여기서 기본 테마로 한 번 그리면 저장 테마로 바뀌며 깜빡인다.
        val initialDesign = Locator.settings.startupDesign()
        setTheme(initialDesign.startingTheme())
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyDesignChrome(initialDesign, isNightMode())
        // 첫 프레임에 곡 목록이 있게 한 뒤 시작 화면을 바로 걷는다. 페이드 동안 빈 목록이 비친다.
        Locator.librarySongs()
        splashScreen.setOnExitAnimationListener { it.remove() }
        setContent {
            val design by Locator.settings.design.collectAsState(initial = initialDesign)
            BandMrTheme(design) {
                BandMrNav(
                    onDesignSelected = { selected ->
                        Locator.settings.setDesignNow(selected)
                        applyDesignChrome(selected, isNightMode())
                    },
                )
            }
        }
    }

    /** 이번 창의 배경·시스템 바, 그리고 다음 콜드 스타트의 스플래시. */
    private fun applyDesignChrome(design: AppDesign, night: Boolean) {
        splashScreen.setSplashScreenTheme(design.startingTheme())
        window.setBackgroundDrawable(ColorDrawable(designColors(design, night).background.toArgb()))
        val dark = designIsDark(design, night)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandMrNav(onDesignSelected: (AppDesign) -> Unit) {
    val design = LocalAppDesign.current
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
                    TextButton(onClick = { showDesigns = true }) { Text("테마") }
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
                SettingsScreen()
            }
        }
    }
    if (showDesigns) {
        DesignPicker(
            selectedDesign = design,
            onSelect = { selected ->
                onDesignSelected(selected)
                showDesigns = false
            },
            onDismiss = { showDesigns = false },
        )
    }
}
