package com.bandmr.app.data

/** Stable preference IDs; changing appearance never changes a song or playback setting. */
enum class AppDesign(val id: String, val label: String, val description: String) {
    MONO("mono", "모노", "흑백과 여백, 선으로 정돈한 음악 라이브러리"),
    AMP("amp", "앰프", "차콜과 앰버, 오디오 장비를 닮은 스튜디오"),
    BLUE("blue", "블루", "산뜻한 블루와 부드러운 앨범 카드"),
    STUDIO("studio", "스튜디오", "차분한 청록색의 기존 디자인 · 시스템 테마"),
    SNOW("snow", "하양", "흰 카드와 검은 글자"),
    INK("ink", "먹", "차가운 검정, 흰 글자"),
    HONG("hong", "주홍", "재생과 음소거만 주홍"),
    MOSS("moss", "숲", "연한 풀빛 바닥, 진한 초록"),
    ;

    companion object {
        fun fromId(id: String?): AppDesign = entries.firstOrNull { it.id == id } ?: MONO
    }
}
