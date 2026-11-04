package com.fgc.emulator;

/** 혈당 시나리오. 알람·장애 처리 로직을 시험하기 위한 패턴. */
public enum Scenario {
    /** 식사 3회, 새벽 현상, 측정 잡음만 있는 평범한 하루 */
    NORMAL,
    /** 매일 02:00~03:30 사이 야간 저혈당 */
    NIGHT_HYPO,
    /** 식후 고혈당 (식사 반응이 큼) */
    POST_MEAL_HYPER,
    /** 매일 15:00부터 20분간 분당 약 4 mg/dL 급강하 */
    RAPID_DROP,
    /** 매일 10:00~10:40 신호 끊김(측정값 없음) */
    SIGNAL_LOSS,
    /** 휴대폰 오프라인: 매일 13:00~14:00 업로드 중단 후 한꺼번에 업로드(backfill) */
    UPLOAD_OUTAGE
}
