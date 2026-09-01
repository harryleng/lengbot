package com.lengbot.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.lengbot.entity.Message;
import com.lengbot.vo.ConversationSearchResultVO;

import java.util.List;

/**
 * 消息服务接口
 *
 * @author lw
 * @since 2026-05-19
 */
public interface MessageService extends IService<Message> {

    /**
     * 分页获取会话消息（按创建时间倒序，返回最新N条）
     *
     * @param sessionId 会话ID
     * @param pageNum   页码
     * @param pageSize  每页数量
     * @return 分页消息列表
     */
    Page<Message> listBySessionIdPage(Long sessionId, int pageNum, int pageSize);

    /**
     * 获取会话的全部消息历史
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    List<Message> listBySessionId(Long sessionId);

    /**
     * 查询某次请求对应的助手消息。
     *
     * @param sessionId 会话 ID
     * @param requestId 请求 ID
     * @return 按创建时间正序排列的助手消息
     */
    List<Message> listAssistantByRequestId(Long sessionId, String requestId);

    /**
     * 查询本轮请求对应的用户输入消息。
     *
     * @param sessionId 会话ID
     * @param requestId 请求ID
     * @return 用户消息，不存在时返回 {@code null}
     */
    Message getUserByRequestId(Long sessionId, String requestId);

    /**
     * 查询指定消息前最近的一条用户消息，用于关联本轮输入附件。
     *
     * @param sessionId 会话 ID
     * @param beforeMessageId 助手消息 ID
     * @return 最近用户消息，不存在时返回 {@code null}
     */
    Message getPreviousUserMessage(Long sessionId, Long beforeMessageId);

    /**
     * 删除会话下的所有消息
     *
     * @param sessionId 会话ID
     */
    void deleteBySessionId(Long sessionId);

    /**
     * 批量删除多个会话下的所有消息（一次 IN 查询 + 一次 IN 删，避免 N+1）。
     * <p>同时级联清理 tool_calls / message_feedback / MinIO 附件，单次调用替代 N 次 {@link #deleteBySessionId}</p>
     *
     * @param sessionIds 会话ID集合
     */
    void deleteBySessionIds(java.util.Collection<Long> sessionIds);

    /**
     * 删除单条消息（物理删除）
     *
     * @param messageId 消息ID
     * @param sessionId 会话ID（用于校验归属）
     */
    void deleteMessage(Long messageId, Long sessionId);

    /**
     * 搜索会话内的消息（内容模糊匹配）
     *
     * @param sessionId 会话ID
     * @param keyword   搜索关键词
     * @param pageNum   页码
     * @param pageSize  每页数量
     * @return 匹配的消息分页列表
     */
    Page<Message> searchBySessionId(Long sessionId, String keyword, int pageNum, int pageSize);

    /**
     * 跨会话搜索消息（按关键词匹配内容，限当前用户的会话）。
     *
     * @param userId  当前登录用户 ID
     * @param keyword 关键词（不可为空）
     * @param limit   最多返回条数
     * @return 每条命中消息 + 所属会话信息
     */
    List<ConversationSearchResultVO> searchConversations(Long userId, String keyword, int limit);

    /**
     * 按 tool_calls 主键读取完整工具执行结果。
     * <p>历史列表场景已剥离 tool_result.result 正文，前端展开「查看结果」时按 toolCallId 拉取完整 JSON，
     * 交由 ToolCallRenderer 按工具类型渲染。</p>
     *
     * @param toolCallId 工具调用记录主键（tool_events 事件中的 toolCallId）
     * @return 完整 tool_output 文本；记录不存在时返回 null
     */
    String getToolResultDetail(Long toolCallId);

    /**
     * 切换消息收藏状态
     *
     * @param messageId 消息ID
     */
    void toggleStar(Long messageId);

    /**
     * 获取当前用户的收藏消息（跨会话，按归属隔离）
     *
     * @param userId   当前登录用户 ID（用于归属过滤，避免泄露其他用户收藏）
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 收藏消息分页列表
     */
    Page<Message> listStarred(Long userId, int pageNum, int pageSize);
}
