package com.lengbot.service;

import com.lengbot.vo.ResearchTaskProjectionVO;

/** 调研任务状态投影服务。 */
public interface ResearchTaskProjectionService {

    /**
     * 按父请求 ID 聚合当前任务状态。
     *
     * @param sessionId 会话 ID
     * @param parentRequestId 父请求 ID
     * @return 请求级调研状态投影
     */
    ResearchTaskProjectionVO getProjection(Long sessionId, String parentRequestId);
}
