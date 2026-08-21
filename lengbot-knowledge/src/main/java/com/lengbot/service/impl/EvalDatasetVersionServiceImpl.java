package com.lengbot.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.common.BizException;
import com.lengbot.entity.EvalDataset;
import com.lengbot.entity.EvalDatasetItem;
import com.lengbot.entity.EvalDatasetVersion;
import com.lengbot.enums.ErrorCode;
import com.lengbot.enums.EvalDatasetVersionStatus;
import com.lengbot.mapper.EvalDatasetVersionMapper;
import com.lengbot.service.EvalDatasetItemService;
import com.lengbot.service.EvalDatasetService;
import com.lengbot.service.EvalDatasetVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 评测数据集版本服务实现类
 *
 * @author lw
 * @since 2026-05-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetVersionServiceImpl extends ServiceImpl<EvalDatasetVersionMapper, EvalDatasetVersion>
        implements EvalDatasetVersionService {

    private final EvalDatasetService datasetService;
    private final EvalDatasetItemService datasetItemService;
    private final ObjectMapper objectMapper;

    @Override
    public EvalDatasetVersion create(Long datasetId, String version) {
        EvalDataset dataset = datasetService.getById(datasetId);
        if (dataset == null) {
            throw new BizException(ErrorCode.EVAL_DATASET_NOT_FOUND);
        }
        // 快照当前数据集的所有条目ID
        List<EvalDatasetItem> items = datasetItemService.listAllByDatasetId(datasetId);
        if (items.isEmpty()) {
            throw new BizException(ErrorCode.EVAL_DATASET_EMPTY);
        }
        List<Long> itemIds = items.stream().map(EvalDatasetItem::getId).collect(Collectors.toList());
        String datasetItemsJson;
        try {
            datasetItemsJson = objectMapper.writeValueAsString(itemIds);
        } catch (Exception e) {
            datasetItemsJson = "[]";
        }

        EvalDatasetVersion dv = new EvalDatasetVersion();
        dv.setDatasetId(datasetId);
        dv.setVersion(version);
        dv.setDataCount(items.size());
        dv.setStatus(EvalDatasetVersionStatus.DRAFT);
        dv.setDatasetItems(datasetItemsJson);
        save(dv);
        return dv;
    }

    @Override
    public List<EvalDatasetVersion> listByDatasetId(Long datasetId) {
        return lambdaQuery()
                .eq(EvalDatasetVersion::getDatasetId, datasetId)
                .orderByDesc(EvalDatasetVersion::getCreateTime)
                .list();
    }

    @Override
    public List<EvalDatasetItem> getItemsByVersionId(Long versionId) {
        EvalDatasetVersion version = getById(versionId);
        if (version == null) {
            throw new BizException(ErrorCode.EVAL_DATASET_VERSION_NOT_FOUND);
        }
        try {
            List<Long> itemIds = objectMapper.readValue(version.getDatasetItems(), new TypeReference<>() {});
            if (itemIds.isEmpty()) {
                return List.of();
            }
            return datasetItemService.listByIds(itemIds);
        } catch (Exception e) {
            log.warn("[评测集版本] 解析数据项失败, versionId={}, error={}", versionId, e.getMessage());
            return List.of();
        }
    }
}
