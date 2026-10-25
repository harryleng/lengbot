-- ============================================================
-- 2026-10-25-001：RAG 评估新增 Faithfulness（接地性/幻觉）指标列
--
-- 背景：
--   RagEvaluationEngine 的 answer_score 是一个 LLM 给的笼统 0~1 综合分，
--   把"幻觉""答非所问""事实错"全搅在一起。本改动补 RAGAS 标志性的
--   Faithfulness：把生成答案拆为事实性声明，逐条判能否从【实际召回的参考文档】
--   推导，统计接地比例。它和 Context Recall（基于 gold_chunk_ids）正交——
--   一个测"检索对不对"，一个测"答案有没有编造"。
--
--   指标存于 eval_rag_result_detail（每题明细），聚合时并入 eval_rag_result
--   的 answer_json（新增 faithfulness 键），不进入 overall_score（保持"检索质量"语义）。
--
-- 部署：本文件只出 SQL，需手动在 lengbot 库执行，应用才能 INSERT 这两个列。
-- ============================================================

ALTER TABLE eval_rag_result_detail
    ADD COLUMN IF NOT EXISTS faithfulness_score     DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS faithfulness_reasoning TEXT;

COMMENT ON COLUMN eval_rag_result_detail.faithfulness_score IS 'Faithfulness 接地性得分 0~1，越高越无幻觉';
COMMENT ON COLUMN eval_rag_result_detail.faithfulness_reasoning IS 'Faithfulness 判定摘要（如 "接地声明 3/4"）';

-- ============================================================
-- 部署后校验（只读）
-- ============================================================
-- SELECT id,
--        left(generated_answer, 30)            AS ans_head,
--        answer_score,
--        faithfulness_score,
--        left(faithfulness_reasoning, 40)      AS faith_reason
-- FROM eval_rag_result_detail
-- WHERE faithfulness_score IS NOT NULL
-- ORDER BY id DESC LIMIT 10;
