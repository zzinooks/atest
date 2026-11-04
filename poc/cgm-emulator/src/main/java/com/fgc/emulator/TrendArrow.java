package com.fgc.emulator;

/**
 * 추세 화살표. 15분 변화율(mg/dL/min) 기준.
 * 임계값(1/2/3)은 업계에서 흔히 쓰는 값을 [가정]으로 채택. 바로잰 Fit의 실제 기준은 UNKNOWN.
 */
public enum TrendArrow {
    FALLING_QUICKLY, FALLING, FALLING_SLIGHTLY, STABLE, RISING_SLIGHTLY, RISING, RISING_QUICKLY, NOT_COMPUTABLE;

    public static TrendArrow fromRate(Double mgdlPerMin) {
        if (mgdlPerMin == null) return NOT_COMPUTABLE;
        double r = mgdlPerMin;
        if (r <= -3) return FALLING_QUICKLY;
        if (r <= -2) return FALLING;
        if (r <= -1) return FALLING_SLIGHTLY;
        if (r < 1) return STABLE;
        if (r < 2) return RISING_SLIGHTLY;
        if (r < 3) return RISING;
        return RISING_QUICKLY;
    }
}
