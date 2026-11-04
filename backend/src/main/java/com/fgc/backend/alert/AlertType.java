package com.fgc.backend.alert;

public enum AlertType {
    URGENT_LOW("매우 낮은 혈당"), LOW("저혈당"), HIGH("고혈당"), RAPID_FALL("혈당 급하강"), RAPID_RISE("혈당 급상승"),
    NO_DATA("혈당 데이터 미수신"), SYNC_ERROR("데이터 연동 오류");

    public final String label;

    AlertType(String label) { this.label = label; }
}
