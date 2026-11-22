-- 简记云端同步服务（sync/）schema（PG 16+）
-- 幂等：CREATE IF NOT EXISTS，首次启动自动建表。

-- 快照版本表：只追加，永不 UPDATE/DELETE（删除只来自保留策略）
CREATE TABLE IF NOT EXISTS snapshots (
    id              BIGSERIAL PRIMARY KEY,      -- 版本号，单调递增；当前版本 = max(id)
    content_hash    TEXT NOT NULL,
    record_count    INT  NOT NULL,              -- 含墓碑
    live_count      INT  NOT NULL,              -- 不含墓碑
    max_updated_at  BIGINT NOT NULL,
    payload_gz      BYTEA NOT NULL,             -- 快照原文 gzip
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_snapshots_created ON snapshots(created_at);
CREATE INDEX IF NOT EXISTS idx_snapshots_hash ON snapshots(content_hash);

-- 镜像表：当前版本的平铺，供查询。每次 sync 成功后在同一事务内 TRUNCATE + 重建。
CREATE TABLE IF NOT EXISTS mirror_records (
    id             TEXT PRIMARY KEY,
    type           TEXT NOT NULL CHECK (type IN ('EXPENSE','INCOME')),
    amount_minor   BIGINT NOT NULL CHECK (amount_minor > 0),
    category_id    TEXT NOT NULL,
    date_epoch_day INT NOT NULL,
    note           TEXT NOT NULL,
    created_at_ms  BIGINT NOT NULL,
    updated_at_ms  BIGINT NOT NULL,
    is_deleted     BOOLEAN NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_mirror_records_date ON mirror_records(date_epoch_day);
CREATE INDEX IF NOT EXISTS idx_mirror_records_category ON mirror_records(category_id);

CREATE TABLE IF NOT EXISTS mirror_categories (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    emoji         TEXT NOT NULL,
    type          TEXT NOT NULL CHECK (type IN ('EXPENSE','INCOME')),
    parent_id     TEXT,                          -- 两级分类的父分类 id；一级分类为 NULL
    sort_order    INT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    is_deleted    BOOLEAN NOT NULL
);
