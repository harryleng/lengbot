package com.lengbot.service.port;

/**
 * 任务执行线程中断端口
 * <p>TaskService 在取消任务时通过此端口中断正在执行的线程（打断阻塞的 LLM 调用等 IO），
 * 由 server 层的任务消费者实现，避免下层 service 反向依赖 config</p>
 *
 * @author finch
 */
public interface TaskInterruptPort {

    /**
     * 中断指定任务的执行线程
     *
     * @param taskId 任务ID
     */
    void interrupt(Long taskId);
}
