-- lengbot · TTS 音色管理表（增量迁移）
-- 适用场景：数据库已通过 init.sql 初始化，需要补建 tts_voice 表。
-- 在目标库执行本文件即可（PostgreSQL 15+）。

CREATE TABLE IF NOT EXISTS tts_voice (
    id              BIGINT          NOT NULL,
    voice_name      VARCHAR(128)    NOT NULL,
    provider        VARCHAR(32)     NOT NULL DEFAULT 'edge-tts',
    friendly_name   VARCHAR(256),
    locale          VARCHAR(32),
    gender          VARCHAR(16),
    favorite        SMALLINT        NOT NULL DEFAULT 0,
    voice_group     VARCHAR(64)     DEFAULT '',
    remark          VARCHAR(512)    DEFAULT '',
    sort_order      INTEGER         NOT NULL DEFAULT 0,
    extra_json      JSONB           DEFAULT '{}',
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

-- 部分唯一索引：仅对未删除行保证 (provider, voice_name) 唯一，避免逻辑删除后重同步冲突
CREATE UNIQUE INDEX IF NOT EXISTS uk_tts_voice_provider_name
    ON tts_voice (provider, voice_name) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_tts_voice_locale ON tts_voice (locale);
CREATE INDEX IF NOT EXISTS idx_tts_voice_gender ON tts_voice (gender);
CREATE INDEX IF NOT EXISTS idx_tts_voice_favorite ON tts_voice (favorite);
CREATE INDEX IF NOT EXISTS idx_tts_voice_group ON tts_voice (voice_group);

COMMENT ON TABLE tts_voice IS 'TTS 音色管理表（缓存 Provider 音色 + 收藏/分组/备注）';
COMMENT ON COLUMN tts_voice.voice_name IS '音色名（Provider ShortName，如 zh-CN-XiaoxiaoNeural）';
COMMENT ON COLUMN tts_voice.favorite IS '是否收藏 0/1';
COMMENT ON COLUMN tts_voice.voice_group IS '自定义分组（自由文本）';
