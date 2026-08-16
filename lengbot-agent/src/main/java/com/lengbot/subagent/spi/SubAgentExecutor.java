package com.lengbot.subagent.spi;

import com.lengbot.service.chat.ChatContext;

/** 单个 SubAgent 任务执行 SPI。 */
public interface SubAgentExecutor {

    /**
     * 执行单个已创建的任务。
     *
     * @param definition 子智能体定义
     * @param task 任务描述
     * @param taskId 任务 ID
     * @param threadId 子线程 ID
     * @param parentThreadId 父线程 ID
     * @param chatContext 当前任务专属上下文
     * @return 执行结果
     */
    ExecutionResult execute(SubAgentDefinition definition, String task, String taskId,
                            String threadId, String parentThreadId, ChatContext chatContext);

    /** 子智能体执行结果。 */
    record ExecutionResult(String reply, String threadId, boolean continued) {
    }
}
