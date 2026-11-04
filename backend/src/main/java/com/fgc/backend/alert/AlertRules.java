package com.fgc.backend.alert;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/** 환자별 알림 기준 (ADR-0004). 기준값은 환자/의료진이 정한다. */
public record AlertRules(
        @Min(40) @Max(100) int urgentLowMgdl,
        @Min(50) @Max(150) int lowMgdl,
        @Min(120) @Max(400) int highMgdl,
        @NotNull @DecimalMin("0.5") @DecimalMax("10.0") BigDecimal rapidRate,
        @Min(10) @Max(720) int noDataMinutes,
        @Min(5) @Max(60) int staleMinutes,
        @Min(5) @Max(240) int repeatMinutes,
        @Min(0) @Max(240) int cooldownMinutes,
        boolean lowEnabled, boolean highEnabled, boolean rapidEnabled, boolean noDataEnabled) {

    public static AlertRules defaults() {
        return new AlertRules(54, 70, 250, new BigDecimal("2.00"), 30, 15, 30, 15, true, true, true, true);
    }

    @AssertTrue(message = "urgentLow < low < high")
    public boolean isOrdered() {
        return urgentLowMgdl < lowMgdl && lowMgdl < highMgdl;
    }
}
