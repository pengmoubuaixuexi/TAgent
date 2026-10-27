-- V075: daily platform quota overrun is carried into the next day.
-- Target: MySQL 8.0, database `ai-agent-station-study`.
-- A request is no longer admitted on a reservation of its output limit: today's usage (platform_reserved_tokens,
-- which now holds input estimates until calls settle) plus carried_tokens is compared with the daily quota.
-- At the quota new runs are refused and running ones finish their current phase; the hard ceiling is 20% above it.
-- carried_tokens is set when the day's row is created: yesterday's usage plus its own carry, minus the daily quota.

ALTER TABLE ai_model_usage_day
  ADD COLUMN carried_tokens BIGINT NOT NULL DEFAULT 0 COMMENT 'Overrun of the previous day counted against this day' AFTER unknown_calls;
