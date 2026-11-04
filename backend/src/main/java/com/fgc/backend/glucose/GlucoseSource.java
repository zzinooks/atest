package com.fgc.backend.glucose;

/** 데이터 출처 (ADR-0002). REA_VERIFIED_API 는 의도적으로 없다. */
public enum GlucoseSource { EMULATOR, HEALTH_CONNECT, NIGHTSCOUT_UPLOAD, BAROZEN_OFFICIAL, MANUAL }
