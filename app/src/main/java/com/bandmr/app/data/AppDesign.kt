package com.bandmr.app.data

/** Stable preference IDs; changing appearance never changes a song or playback setting. */
enum class AppDesign(val id: String, val label: String, val description: String) {
    MONO("mono", "모노", "흑백과 여백, 선으로 정돈한 음악 라이브러리"),
    AMP("amp", "앰프", "차콜과 앰버, 오디오 장비를 닮은 스튜디오"),
    BLUE("blue", "블루", "산뜻한 블루와 부드러운 앨범 카드"),
    STUDIO("studio", "스튜디오", "차분한 청록색의 기존 디자인 · 시스템 테마"),
    MIXDECK("mixdeck", "믹스덱", "무채색 위에 스템 채널 컬러만 — DAW 믹서"),
    ALBUM("album", "앨범", "아트워크 색이 화면을 물들이는 앰비언트 · 시스템 테마"),
    GRID("grid", "그리드", "도트 그리드와 세이프티 레드의 테크니컬 도면"),
    CONSOLE("console", "콘솔", "크림 페이스플레이트와 VU 미터의 빈티지 장비"),
    AURORA("aurora", "오로라", "빛무리 위에 떠 있는 젖빛 글래스"),
    ;

    companion object {
        fun fromId(id: String?): AppDesign = entries.firstOrNull { it.id == id } ?: MONO
    }
}
