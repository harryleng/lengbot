package com.lengbot.service;

import com.lengbot.dto.ChatRequestDTO;
import com.lengbot.vo.RagReferenceVO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI对话服务接口
 *
 * @author lw
 * @since 2026-05-19
 */
public interface ChatService {

    /**
     * 同步对话
     *
     * @param request 对话请求
     * @return AI回复
     */
    String chat(ChatRequestDTO request);

    /**
     * 流式对话（SSE）
     *
     * @param request 对话请求
     * @return 流式回复
     */
    Flux<String> chatStream(ChatRequestDTO request);

    /**
     * 停止进行中的流式对话
     * <p>置中断标记使 in-flight LLM 轮次立即停止，并连带取消该请求下运行中的 SubAgent 子任务。</p>
     *
     * @param requestId 对话请求 ID
     * @param userId    发起停止的用户 ID（用于归属校验）
     */
    void stopStream(String requestId, Long userId);

    /**
     * 获取会话的RAG引用信息
     * <p>用于在对话完成后获取检索到的文献引用</p>
     *
     * @param sessionId 会话ID
     * @param agentId AgentID
     * @param question 用户问题
     * @return RAG引用列表
     */
    List<RagReferenceVO> getRagReferences(Long sessionId, Long agentId, String question);
}
