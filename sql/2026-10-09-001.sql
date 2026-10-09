-- Phase 1 父子分块（parent-child chunking）
-- chunk 表增加 parent_content 列：缓存父块全文，检索命中子块时回填给大模型以保上下文完整。
-- 单级分块（general/book/...）该列为 NULL，不影响现有行为。
ALTER TABLE chunk ADD COLUMN IF NOT EXISTS parent_content TEXT;

COMMENT ON COLUMN chunk.parent_content IS
    '父子分块模式下父块全文缓存；命中子块时回填给大模型；单级分块为NULL';
