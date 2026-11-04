-- Family Glucose Care 초기 스키마 (ARCH-001)

CREATE TABLE users (
    id              uuid PRIMARY KEY,
    email           text        NOT NULL,
    password_hash   text        NOT NULL,
    display_name    text        NOT NULL,
    admin           boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL,
    deleted_at      timestamptz
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email)) WHERE deleted_at IS NULL;

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  text        NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL
);

CREATE TABLE patient_profiles (
    id              uuid PRIMARY KEY,
    user_id         uuid        NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    time_zone       text        NOT NULL DEFAULT 'Asia/Seoul',
    notion_enabled  boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL
);

CREATE TABLE family_groups (
    id          uuid PRIMARY KEY,
    patient_id  uuid        NOT NULL UNIQUE REFERENCES patient_profiles (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL
);

CREATE TABLE family_members (
    id                  uuid PRIMARY KEY,
    group_id            uuid        NOT NULL REFERENCES family_groups (id) ON DELETE CASCADE,
    user_id             uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    relation            text        NOT NULL,
    can_view_glucose    boolean     NOT NULL,
    can_receive_alerts  boolean     NOT NULL,
    can_write_notes     boolean     NOT NULL,
    status              text        NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    joined_at           timestamptz NOT NULL,
    revoked_at          timestamptz,
    UNIQUE (group_id, user_id)
);

CREATE TABLE sharing_invitations (
    id                  uuid PRIMARY KEY,
    group_id            uuid        NOT NULL REFERENCES family_groups (id) ON DELETE CASCADE,
    code_hash           text        NOT NULL UNIQUE,
    relation            text        NOT NULL,
    can_view_glucose    boolean     NOT NULL,
    can_receive_alerts  boolean     NOT NULL,
    can_write_notes     boolean     NOT NULL,
    expires_at          timestamptz NOT NULL,
    accepted_by         uuid REFERENCES users (id) ON DELETE SET NULL,
    accepted_at         timestamptz,
    revoked_at          timestamptz,
    created_at          timestamptz NOT NULL
);

-- 공유 동의 이력 (변경 불가 로그)
CREATE TABLE sharing_consents (
    id              bigserial PRIMARY KEY,
    patient_id      uuid        NOT NULL REFERENCES patient_profiles (id) ON DELETE CASCADE,
    member_user_id  uuid        NOT NULL,
    actor_user_id   uuid        NOT NULL,
    action          text        NOT NULL CHECK (action IN ('GRANTED', 'UPDATED', 'REVOKED', 'LEFT')),
    permissions     text        NOT NULL,
    created_at      timestamptz NOT NULL
);

CREATE TABLE devices (
    id            uuid PRIMARY KEY,
    user_id       uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    platform      text        NOT NULL,
    push_token    text        NOT NULL UNIQUE,
    last_seen_at  timestamptz NOT NULL,
    created_at    timestamptz NOT NULL,
    disabled_at   timestamptz
);

CREATE TABLE data_sources (
    id                    uuid PRIMARY KEY,
    patient_id            uuid        NOT NULL REFERENCES patient_profiles (id) ON DELETE CASCADE,
    type                  text        NOT NULL CHECK (type IN ('EMULATOR', 'HEALTH_CONNECT', 'NIGHTSCOUT_UPLOAD', 'BAROZEN_OFFICIAL')),
    base_url              text,
    username              text,
    secret_ciphertext     bytea,
    status                text        NOT NULL CHECK (status IN ('ACTIVE', 'PAUSED')),
    consecutive_failures  int         NOT NULL DEFAULT 0,
    last_success_at       timestamptz,
    last_error            text,
    last_error_at         timestamptz,
    created_at            timestamptz NOT NULL
);

CREATE TABLE glucose_readings (
    id               bigserial PRIMARY KEY,
    patient_id       uuid        NOT NULL REFERENCES patient_profiles (id) ON DELETE CASCADE,
    value_mgdl       smallint,
    flag             text        NOT NULL CHECK (flag IN ('NORMAL', 'LOW_OUT_OF_RANGE', 'HIGH_OUT_OF_RANGE')),
    measured_at      timestamptz NOT NULL,
    measured_offset  text,
    received_at      timestamptz NOT NULL,
    source           text        NOT NULL,
    external_id      text        NOT NULL,
    source_trend     text,
    created_at       timestamptz NOT NULL,
    CHECK ((flag = 'NORMAL') = (value_mgdl IS NOT NULL)),
    UNIQUE (patient_id, source, external_id)
);
CREATE INDEX ix_glucose_patient_measured ON glucose_readings (patient_id, measured_at DESC);

CREATE TABLE alert_rules (
    patient_id          uuid PRIMARY KEY REFERENCES patient_profiles (id) ON DELETE CASCADE,
    urgent_low_mgdl     int         NOT NULL,
    low_mgdl            int         NOT NULL,
    high_mgdl           int         NOT NULL,
    rapid_rate          numeric(4, 2) NOT NULL,
    no_data_minutes     int         NOT NULL,
    stale_minutes       int         NOT NULL,
    repeat_minutes      int         NOT NULL,
    cooldown_minutes    int         NOT NULL,
    low_enabled         boolean     NOT NULL,
    high_enabled        boolean     NOT NULL,
    rapid_enabled       boolean     NOT NULL,
    no_data_enabled     boolean     NOT NULL,
    updated_at          timestamptz NOT NULL,
    CHECK (urgent_low_mgdl < low_mgdl AND low_mgdl < high_mgdl)
);

CREATE TABLE alerts (
    id                uuid PRIMARY KEY,
    patient_id        uuid        NOT NULL REFERENCES patient_profiles (id) ON DELETE CASCADE,
    type              text        NOT NULL,
    status            text        NOT NULL CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    reading_id        bigint,
    measured_at       timestamptz,
    opened_at         timestamptz NOT NULL,
    last_notified_at  timestamptz,
    acknowledged_at   timestamptz,
    acknowledged_by   uuid,
    resolved_at       timestamptz
);
-- 같은 환자·같은 종류의 미해소 알림은 하나만
CREATE UNIQUE INDEX ux_alerts_active ON alerts (patient_id, type) WHERE status <> 'RESOLVED';
CREATE INDEX ix_alerts_patient_opened ON alerts (patient_id, opened_at DESC);

CREATE TABLE notification_deliveries (
    id                   bigserial PRIMARY KEY,
    alert_id             uuid REFERENCES alerts (id) ON DELETE CASCADE,
    recipient_user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_id            uuid REFERENCES devices (id) ON DELETE CASCADE,
    kind                 text        NOT NULL,
    title                text        NOT NULL,
    body                 text        NOT NULL,
    status               text        NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'NO_DEVICE')),
    attempts             int         NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz NOT NULL,
    last_error           text,
    provider             text,
    provider_message_id  text,
    created_at           timestamptz NOT NULL,
    sent_at              timestamptz
);
CREATE INDEX ix_deliveries_pending ON notification_deliveries (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE health_events (
    id                uuid PRIMARY KEY,
    patient_id        uuid        NOT NULL REFERENCES patient_profiles (id) ON DELETE CASCADE,
    author_user_id    uuid        NOT NULL,
    type              text        NOT NULL CHECK (type IN ('MEAL', 'EXERCISE', 'MEDICATION', 'NOTE')),
    occurred_at       timestamptz NOT NULL,
    title             text        NOT NULL,
    detail            text,
    carbs_g           int,
    duration_minutes  int,
    created_at        timestamptz NOT NULL
);
CREATE INDEX ix_events_patient_time ON health_events (patient_id, occurred_at DESC);

CREATE TABLE sync_history (
    id              bigserial PRIMARY KEY,
    data_source_id  uuid        NOT NULL REFERENCES data_sources (id) ON DELETE CASCADE,
    patient_id      uuid        NOT NULL,
    started_at      timestamptz NOT NULL,
    finished_at     timestamptz NOT NULL,
    status          text        NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    fetched         int         NOT NULL,
    inserted        int         NOT NULL,
    duplicates      int         NOT NULL,
    rejected        int         NOT NULL,
    error           text
);
CREATE INDEX ix_sync_source_time ON sync_history (data_source_id, started_at DESC);

CREATE TABLE notion_sync (
    id            bigserial PRIMARY KEY,
    patient_id    uuid        NOT NULL REFERENCES patient_profiles (id) ON DELETE CASCADE,
    period        text        NOT NULL CHECK (period IN ('DAY', 'WEEK')),
    period_start  date        NOT NULL,
    status        text        NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    page_id       text,
    attempts      int         NOT NULL,
    last_error    text,
    updated_at    timestamptz NOT NULL,
    UNIQUE (patient_id, period, period_start)
);

-- 감사 로그: 혈당값 등 건강정보는 저장하지 않는다
CREATE TABLE audit_logs (
    id             bigserial PRIMARY KEY,
    actor_user_id  uuid,
    patient_id     uuid,
    action         text        NOT NULL,
    detail         text,
    created_at     timestamptz NOT NULL
);
CREATE INDEX ix_audit_patient_time ON audit_logs (patient_id, created_at DESC);
