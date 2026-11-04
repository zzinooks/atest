package com.fgc.core

import java.time.Duration
import java.time.Instant

/**
 * 화면 표시 규칙 (ARCH-001, REQ-001). 혈당값만 크게 보여주지 않고 측정 시각·신선도를 함께 보여준다.
 * 오래된 값은 흐리게 + 경고 문구, 미수신은 값 대신 상태를 보여준다.
 */
enum class Level { URGENT_LOW, LOW, IN_RANGE, HIGH, UNKNOWN }

data class DisplayState(
    val valueText: String,
    val unit: String,
    val trendArrow: String,
    val trendDescription: String,
    val level: Level,
    val freshness: Freshness,
    val ageText: String,
    val dimmed: Boolean,
    val banner: String?,
)

object GlucoseDisplay {
    fun present(latest: Latest, rules: AlertRules?, now: Instant = latest.serverTime): DisplayState {
        val r = latest.reading
            ?: return DisplayState("--", "mg/dL", "", "", Level.UNKNOWN, Freshness.NO_DATA, "수신 기록 없음", true,
                "아직 수신된 혈당이 없습니다. 데이터 연결 상태를 확인하세요.")
        val value = when (r.flag) {
            Flag.LOW_OUT_OF_RANGE -> "LO"
            Flag.HIGH_OUT_OF_RANGE -> "HI"
            Flag.NORMAL -> r.valueMgdl.toString()
        }
        val stale = latest.freshness != Freshness.FRESH
        val (arrow, desc) = if (stale) "" to "" else trend(latest.ratePerMinute)
        val age = ageText(Duration.between(r.measuredAt, now))
        val banner = if (stale) "마지막 측정이 $age 입니다. 지금 혈당과 다를 수 있습니다." else null
        return DisplayState(value, "mg/dL", arrow, desc, level(r, rules), latest.freshness, age, stale, banner)
    }

    fun level(r: Reading, rules: AlertRules?): Level {
        val low = rules?.lowMgdl ?: 70
        val urgent = rules?.urgentLowMgdl ?: 54
        val high = rules?.highMgdl ?: 250
        return when {
            r.flag == Flag.LOW_OUT_OF_RANGE -> Level.URGENT_LOW
            r.flag == Flag.HIGH_OUT_OF_RANGE -> Level.HIGH
            r.valueMgdl == null -> Level.UNKNOWN
            r.valueMgdl <= urgent -> Level.URGENT_LOW
            r.valueMgdl <= low -> Level.LOW
            r.valueMgdl >= high -> Level.HIGH
            else -> Level.IN_RANGE
        }
    }

    /** 서버가 저장된 측정값으로 계산한 변화율(mg/dL/분) 기준. 소스 제공 화살표는 기준이 불명확해 쓰지 않는다. */
    fun trend(rate: Double?): Pair<String, String> = when {
        rate == null -> "" to "변화 추세 계산 불가"
        rate <= -3 -> "↓↓" to "빠르게 떨어지는 중"
        rate <= -2 -> "↓" to "떨어지는 중"
        rate <= -1 -> "↘" to "조금 떨어지는 중"
        rate < 1 -> "→" to "안정"
        rate < 2 -> "↗" to "조금 오르는 중"
        rate < 3 -> "↑" to "오르는 중"
        else -> "↑↑" to "빠르게 오르는 중"
    }

    fun ageText(d: Duration): String {
        val m = d.toMinutes()
        return when {
            m < 1 -> "방금 전"
            m < 60 -> "${m}분 전"
            m < 60 * 24 -> "${m / 60}시간 ${m % 60}분 전"
            else -> "${m / (60 * 24)}일 전"
        }
    }
}

/** 그래프: 측정 간격이 maxGap 보다 벌어지면 선을 끊는다 (끊긴 구간을 이어 그려 "있었던 것처럼" 보이지 않게). */
object GraphSeries {
    data class Point(val at: Instant, val mgdl: Int, val outOfRange: Boolean)

    fun segments(readings: List<Reading>, maxGap: Duration = Duration.ofMinutes(10),
                 loDisplay: Int = 40, hiDisplay: Int = 400): List<List<Point>> {
        val out = mutableListOf<MutableList<Point>>()
        var prev: Instant? = null
        for (r in readings.sortedBy { it.measuredAt }) {
            val p = when (r.flag) {
                Flag.LOW_OUT_OF_RANGE -> Point(r.measuredAt, loDisplay, true)
                Flag.HIGH_OUT_OF_RANGE -> Point(r.measuredAt, hiDisplay, true)
                Flag.NORMAL -> Point(r.measuredAt, r.valueMgdl!!, false)
            }
            if (prev == null || Duration.between(prev, r.measuredAt) > maxGap) out.add(mutableListOf())
            out.last().add(p)
            prev = r.measuredAt
        }
        return out
    }
}
