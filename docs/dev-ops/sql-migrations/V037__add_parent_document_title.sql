-- V037: Add `title` column to ai_parent_document.
-- 第 61 轮 Phase 2 (A 方案 parent title)：ingest 时调小模型为每个 parent 生成 5-15 字精炼小标题,
-- 用作 RAG 引用依据卡片的可读 source，比原始文件名 / parent_id UUID 信息量大得多。
-- title 为 NULL 时前端 fallback 到 source → knowledge → parent_id。
-- 第 64 轮 Codex review 后改幂等：用 IF NOT EXISTS，重复执行不会 duplicate column。

SET NAMES utf8mb4;
USE `ai-agent-station-study`;

ALTER TABLE `ai_parent_document`
    ADD COLUMN IF NOT EXISTS `title` VARCHAR(128) DEFAULT NULL COMMENT 'LLM 生成的 chunk 精炼小标题（5-15 字），用于前端引用展示' AFTER `source`;
