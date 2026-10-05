package com.bandmr.app.data

/** 저장 키는 [id]. 나열 순서는 테마 선택 시트에 그대로 나가므로 비슷한 색끼리 둔다. */
enum class AppDesign(val id: String, val label: String, val description: String) {
    MONO("mono", "모노", "흑백과 여백, 선으로 정돈한 음악 라이브러리"),
    SNOW("snow", "스노우", "흰 카드와 얇은 그림자, 검은 글자만 남긴 목록"),
    GRID("grid", "그리드", "도트 그리드와 세이프티 레드의 테크니컬 도면"),
    INK("ink", "나이트", "차가운 검정과 따뜻한 흰 글자, 불 꺼진 연습실"),
    MIXDECK("mixdeck", "믹스덱", "무채색 위에 스템 채널 컬러만 — DAW 믹서"),
    AMP("amp", "앰프", "차콜과 앰버, 오디오 장비를 닮은 스튜디오"),
    AURORA("aurora", "오로라", "빛무리 위에 떠 있는 젖빛 글래스"),
    CONSOLE("console", "콘솔", "크림 페이스플레이트와 VU 미터의 빈티지 장비"),
    ALBUM("album", "앨범", "아트워크 색이 화면을 물들이는 앰비언트 · 시스템 테마"),
    BLUE("blue", "블루", "산뜻한 블루와 부드러운 앨범 카드"),
    STUDIO("studio", "스튜디오", "차분한 청록색의 기존 디자인 · 시스템 테마"),
    MOSS("moss", "가든", "연한 풀빛과 진한 초록, 둥글게 앉은 연습실"),
    ;

    companion object {
        fun fromId(id: String?): AppDesign = entries.firstOrNull { it.id == id } ?: MONO
    }
}
