CREATE DATABASE IF NOT EXISTS {db};

CREATE TABLE IF NOT EXISTS {db}.discord_events
(
    event_id    UUID,
    event_time  DateTime64(3, 'UTC'),
    received_at DateTime64(3, 'UTC') DEFAULT now64(3),
    guild_id    UInt64,
    event_type  LowCardinality(String),
    channel_id  Nullable(UInt64),
    user_id     Nullable(UInt64),
    message_id  Nullable(UInt64),
    payload     JSON
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (guild_id, event_type, event_time);
