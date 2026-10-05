package com.bandmr.app.ui.theme

import com.bandmr.app.R
import com.bandmr.app.data.AppDesign

/**
 * 콜드 스타트 스플래시 테마. [android.window.SplashScreen.setSplashScreenTheme]가 이름을 저장하므로
 * 스타일 이름을 바꾸면 다음 실행의 시작 화면이 기본(검정)으로 돌아간다.
 */
fun AppDesign.startingTheme(): Int = when (this) {
    AppDesign.MONO -> R.style.Theme_BandMR_Mono
    AppDesign.SNOW -> R.style.Theme_BandMR_Snow
    AppDesign.INK -> R.style.Theme_BandMR_Ink
    AppDesign.AMP -> R.style.Theme_BandMR_Amp
    AppDesign.AURORA -> R.style.Theme_BandMR_Aurora
    AppDesign.CONSOLE -> R.style.Theme_BandMR_Console
    AppDesign.ALBUM -> R.style.Theme_BandMR_Album
    AppDesign.BLUE -> R.style.Theme_BandMR_Blue
    AppDesign.STUDIO -> R.style.Theme_BandMR_Studio
    AppDesign.MOSS -> R.style.Theme_BandMR_Moss
}
