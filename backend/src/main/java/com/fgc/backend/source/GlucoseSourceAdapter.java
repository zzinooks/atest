package com.fgc.backend.source;

import com.fgc.backend.glucose.GlucoseSource;
import com.fgc.backend.glucose.RawReading;

import java.time.Instant;
import java.util.List;

/** 서버가 가져오는(pull) 방식의 수집 어댑터. 앱 업로드·Nightscout 같은 push 방식은 API 로 들어온다. */
public interface GlucoseSourceAdapter {
    String type();

    GlucoseSource source();

    /** measuredAt >= from 인 측정값. 실패 시 SourceException. */
    List<RawReading> fetch(DataSource source, Instant from);

    class SourceException extends RuntimeException {
        public SourceException(String message) { super(message); }
    }
}
