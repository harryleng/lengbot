package com.lengbot.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lengbot.common.Result;
import com.lengbot.dto.ModelProviderCheckDTO;
import com.lengbot.dto.ModelProviderDTO;
import com.lengbot.entity.ModelProvider;
import com.lengbot.model.ConfigField;
import com.lengbot.model.FetchedModel;
import com.lengbot.model.ModelFactory;
import com.lengbot.service.ModelProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "模型提供商管理", description = "模型提供商的增删改查")
@RestController
@RequestMapping("/api/model-providers")
@RequiredArgsConstructor
public class ModelProviderController {

    private final ModelProviderService modelProviderService;
    private final ModelFactory modelFactory;

    @Operation(summary = "新增模型提供商")
    @PostMapping
    public Result<ModelProvider> create(@Valid @RequestBody ModelProviderDTO request) {
        return Result.ok(modelProviderService.create(request));
    }

    @Operation(summary = "更新模型提供商")
    @PutMapping
    public Result<ModelProvider> update(@Valid @RequestBody ModelProviderDTO request) {
        ModelProvider provider = modelProviderService.update(request);
        // 凭证变更后清除缓存，下次调用时重新创建ChatModel
        modelFactory.invalidateCache(request.getId());
        return Result.ok(provider);
    }

    @Operation(summary = "删除模型提供商")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        modelProviderService.deleteById(id);
        // 删除后清除该 provider 的 ChatModel 缓存，避免残留实例被后续请求复用
        modelFactory.invalidateCache(id);
        return Result.ok();
    }

    @Operation(summary = "分页查询模型提供商")
    @GetMapping
    public Result<Page<ModelProvider>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(modelProviderService.listPage(pageNum, pageSize));
    }

    @Operation(summary = "获取单个模型提供商")
    @GetMapping("/{id}")
    public Result<ModelProvider> getById(@PathVariable Long id) {
        return Result.ok(modelProviderService.getById(id));
    }

    @Operation(summary = "获取提供商的配置字段定义（用于前端动态渲染表单）")
    @GetMapping("/{id}/config-fields")
    public Result<List<ConfigField>> getConfigFields(@PathVariable Long id) {
        return Result.ok(modelFactory.getConfigFields(id));
    }

    @Operation(summary = "获取提供商的模型能力字段定义（多模态、联网搜索等）")
    @GetMapping("/{id}/model-capabilities")
    public Result<List<ConfigField>> getModelCapabilities(@PathVariable Long id) {
        return Result.ok(modelFactory.getModelCapabilities(id));
    }

    @Operation(summary = "获取提供商类型的默认模型")
    @GetMapping("/default-model")
    public Result<String> getDefaultModelId(@RequestParam com.lengbot.enums.ModelProviderType type) {
        return Result.ok(modelFactory.getDefaultModelId(type));
    }

    @Operation(summary = "检查模型提供商连通性（已保存的提供商）")
    @GetMapping("/{id}/check")
    public Result<String> checkConnectivity(@PathVariable Long id) {
        return Result.ok(modelFactory.checkConnectivity(id));
    }

    @Operation(summary = "检查模型提供商连通性（表单实时数据）")
    @PostMapping("/check")
    public Result<String> checkConnectivityByForm(@Valid @RequestBody ModelProviderCheckDTO request) {
        return Result.ok(modelFactory.checkConnectivityByForm(request.getType(), request.getApiKey(), request.getBaseUrl(),
                request.getModelId(), request.getCompletionsPath()));
    }

    @Operation(summary = "联网拉取提供商下的可用模型列表")
    @GetMapping("/{id}/fetch-models")
    public Result<List<FetchedModel>> fetchModels(@PathVariable Long id) {
        return Result.ok(modelFactory.fetchModels(id));
    }

    @Operation(summary = "切换模型提供商状态（启用/禁用）")
    @PatchMapping("/{id}/status")
    public Result<Void> toggleStatus(@PathVariable Long id, @RequestParam String status) {
        modelProviderService.updateStatus(id, status);
        return Result.ok();
    }

    @Operation(summary = "获取模型提供商预设")
    @GetMapping("/presets")
    public Result<List<com.lengbot.vo.ModelProviderPresetVO>> listPresets() {
        return Result.ok(modelProviderService.listPresets());
    }

    @Operation(summary = "获取所有提供商及其模型（按类型过滤）")
    @GetMapping("/with-models")
    public Result<List<com.lengbot.service.ModelProviderService.ProviderWithModelsVO>> listWithModels(
            @RequestParam(required = false) String type) {
        return Result.ok(modelProviderService.listWithModels(type));
    }

    @Operation(summary = "刷新模型提供商缓存（从数据库重新加载）")
    @PostMapping("/refresh-cache")
    public Result<Void> refreshCache() {
        modelFactory.invalidateAllCache();
        return Result.ok();
    }
}
