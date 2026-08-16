package com.lengbot.service;

import com.lengbot.entity.Knowledge;

import java.util.List;
import java.util.Map;

/** 统一知识库检索入口，屏蔽本地向量库和外部 Dataset 的实现差异。 */
public interface KnowledgeRetrievalService {

    /** 根据知识库类型执行检索，返回统一的内容、分数和来源字段。 */
    List<Map<String, Object>> retrieve(Knowledge knowledge, String query, int topK,
                                       double threshold, Map<String, Object> queryParams);
}
