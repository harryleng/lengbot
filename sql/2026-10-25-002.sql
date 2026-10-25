-- ============================================================
-- 2026-10-25-002：RAG 评估新增 Answer-Relevancy（答案相关性/跑题检测）指标列
--
-- 背景：
--   step 2 已补 Faithfulness（接地性/幻觉检测，基于"答案声明能否从召回文档推导"）。
--   本改动补 RAGAS 另一标志性指标 Answer-Relevancy：让 LLM 从【AI生成的答案】反向推断若干
--   问题，对原问题与这些"反推问题"做 embedding 余弦相似度取平均。它和 Faithfulness 正交——
--   - Faithfulness 测"答案有没有编造"（接地性，基于召回文档）
--   - Answer-Relevancy 测"答案有没有跑题"（相关性，基于原问题语义）
--   低分 = 答非所问、废话多、或答了与原始问题无关的内容。
--
--   实现：RAGAS 原生算法（LLM 反推问题 + 余弦），依赖 TextEmbeddingService，离线评测不影响线上。
--   指标存于 eval_rag_result_detail（每题明细），聚合时并入 eval_rag_result 的 answer_json
--   （新增 answerRelevancy 键），不进入 overall_score（保持"检索质量"语义）。
--
--   注：若 embedding 服务对当前知识库不可用，该题为 null（聚合时自动跳过，不会拉低均值）。
--
-- 部署：本文件只出 SQL，需手动在 lengbot 库执行，应用才能 INSERT 这两个列。
-- ============================================================

ALTER TABLE eval_rag_result_detail
    ADD COLUMN IF NOT EXISTS answer_relevancy_score     DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS answer_relevancy_reasoning TEXT;

COMMENT ON COLUMN eval_rag_result_detail.answer_relevancy_score IS 'Answer-Relevancy 答案相关性 0~1，越高越切题（抓跑题/答非所问）';
COMMENT ON COLUMN eval_rag_result_detail.answer_relevancy_reasoning IS 'Answer-Relevancy 判定摘要（如 "基于 3 个反推问题的平均余弦"）';

-- ============================================================
-- 部署后校验（只读）
-- ============================================================
-- SELECT id,
--        left(generated_answer, 30)            AS ans_head,
--        answer_score,
--        faithfulness_score,
--        answer_relevancy_score,
--        left(answer_relevancy_reasoning, 40)  AS rel_reason
-- FROM eval_rag_result_detail
-- WHERE answer_relevancy_score IS NOT NULL
-- ORDER BY id DESC LIMIT 10;
