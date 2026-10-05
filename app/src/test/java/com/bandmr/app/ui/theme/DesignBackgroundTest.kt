package com.bandmr.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.bandmr.app.data.AppDesign
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DesignBackgroundTest {

    @Test
    fun `스플래시 배경색은 테마 배경과 같다`() {
        val light = readColors("src/main/res/values/colors.xml")
        val night = readColors("src/main/res/values-night/colors.xml")
        val names = mapOf(
            AppDesign.MONO to "theme_mono_background",
            AppDesign.SNOW to "theme_snow_background",
            AppDesign.INK to "theme_ink_background",
            AppDesign.AMP to "theme_amp_background",
            AppDesign.AURORA to "theme_aurora_background",
            AppDesign.CONSOLE to "theme_console_background",
            AppDesign.ALBUM to "theme_album_background",
            AppDesign.BLUE to "theme_blue_background",
            AppDesign.STUDIO to "theme_studio_background",
            AppDesign.MOSS to "theme_moss_background",
        )
        AppDesign.entries.forEach { design ->
            val name = names.getValue(design)
            listOf(false, true).forEach { systemDark ->
                val hex = if (systemDark) night[name] ?: light.getValue(name) else light.getValue(name)
                assertEquals(
                    "$design night=$systemDark",
                    Color(hex),
                    designColors(design, systemDark).background,
                )
            }
        }
    }

    private fun readColors(path: String): Map<String, Long> {
        val file = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("색 리소스 없음: $path (cwd=${File(".").absolutePath})")
        val pattern = Regex("""name="(theme_[a-z0-9_]+)"\s*>\s*#([0-9A-Fa-f]{6})""")
        return pattern.findAll(file.readText()).associate { match ->
            match.groupValues[1] to (0xFF000000L or match.groupValues[2].toLong(16))
        }
    }
}
