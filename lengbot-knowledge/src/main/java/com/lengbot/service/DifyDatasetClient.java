package com.lengbot.service;

import java.util.List;
import java.util.Map;

/** Dify Dataset HTTP 客户端抽象，隔离外部 API 细节。 */
public interface DifyDatasetClient {

    /** 调用 Dataset 检索接口。 */
    List<Map<String, Object>> retrieve(String apiUrl, String datasetId, String token,
                                       String query, int topK, double threshold, String searchMode);
}
