CREATE TABLE url_mappings (
    id          BIGSERIAL PRIMARY KEY,
    short_code  VARCHAR(10)   NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP,
    click_count BIGINT        NOT NULL DEFAULT 0,
    ip_address  VARCHAR(45),

    CONSTRAINT uq_short_code UNIQUE (short_code)
);

CREATE INDEX idx_url_mappings_short_code ON url_mappings (short_code);
CREATE INDEX idx_url_mappings_expires_at ON url_mappings (expires_at) WHERE expires_at IS NOT NULL;
