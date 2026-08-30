package com.lengbot.controller;

import com.lengbot.common.Result;
import com.lengbot.tts.TtsAudio;
import com.lengbot.tts.TtsRequest;
import com.lengbot.service.TtsVoiceManageService;
import com.lengbot.tts.TtsService;
import com.lengbot.vo.TtsVoiceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TTS（语音合成）接口。
 * <p>
 * 数字人播报、消息朗读等场景均通过本接口获取音频：
 * <ul>
 *   <li>{@code POST /api/tts/synthesize}：合成音频，直接返回音频字节流（audio/mpeg 或 audio/wav）</li>
 *   <li>{@code GET  /api/tts/voices}：列出当前 Provider 支持的全部音色</li>
 *   <li>{@code GET  /api/tts/provider}：返回当前生效的 Provider 及全部可选 Provider</li>
 *   <li>{@code POST /api/tts/provider}：运行时切换当前生效的 Provider（无需重启服务）</li>
 * </ul>
 * 引擎由后端 {@code lengbot.tts.provider} 配置选择（mock / edge-tts），并可在运行时切换。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Tag(name = "语音合成(TTS)", description = "后端 TTS 合成、音色列表、引擎查询与切换")
@RestController
@RequestMapping("/api/tts")
@RequiredArgsConstructor
public class TtsController {

    private final TtsService ttsService;
    private final TtsVoiceManageService voiceManageService;

    /**
     * 合成语音。
     * 直接返回音频字节，Content-Type 由 Provider 决定（mp3 / wav）。
     */
    @Operation(summary = "合成语音", description = "将文本合成为语音音频，返回音频字节流。可指定 voice/rate/pitch/format，缺省使用服务端默认配置。")
    @PostMapping(value = "/synthesize", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> synthesize(@RequestBody TtsRequest request) {
        TtsAudio audio = ttsService.synthesize(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(audio.getContentType()));
        // 禁用缓存，保证每次播报都是最新合成
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(audio.getData(), headers, HttpStatus.OK);
    }

    /**
     * 列出受管音色（带筛选）。
     * <p>优先读本地缓存（离线可用），首次为空时自动从 Provider 懒同步一次。</p>
     */
    @Operation(summary = "音色列表(受管)", description = "返回已缓存的 TTS 音色，支持按引擎/语言/性别/收藏/分组/关键字筛选；含收藏、分组、备注等管理字段。")
    @GetMapping("/voices")
    public Result<List<TtsVoiceView>> listVoices(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String locale,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) Boolean favorite,
            @RequestParam(required = false) String group,
            @RequestParam(required = false) String keyword) {
        return Result.ok(voiceManageService.list(provider, locale, gender, favorite, group, keyword));
    }

    /**
     * 从指定/当前 Provider 同步音色到本地缓存（upsert，保留收藏/分组/备注）。
     */
    @Operation(summary = "同步音色", description = "从 Provider 拉取最新音色清单并写入本地缓存；返回新增/变更数量。")
    @PostMapping("/voices/sync")
    public Result<Map<String, Object>> syncVoices(@RequestParam(required = false) String provider) {
        int synced = voiceManageService.syncFromProvider(provider);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("synced", synced);
        return Result.ok(data);
    }

    /**
     * 局部更新某音色的元数据（收藏 / 分组 / 备注）。
     */
    @Operation(summary = "更新音色元数据", description = "局部更新收藏、分组或备注；body 中只传需要变更的字段。")
    @PatchMapping("/voices/{voiceName}")
    public Result<Void> updateVoiceMeta(
            @PathVariable String voiceName,
            @RequestParam(required = false) String provider,
            @RequestBody(required = false) Map<String, Object> body) {
        voiceManageService.updateMeta(voiceName, provider, body);
        return Result.ok();
    }

    /**
     * 列出所有被使用的分组（去重、去空、排序），用于前端筛选与下拉。
     */
    @Operation(summary = "音色分组列表", description = "返回当前已存在的音色分组名称列表。")
    @GetMapping("/voices/groups")
    public Result<List<String>> listVoiceGroups() {
        return Result.ok(voiceManageService.listGroups());
    }

    /**
     * 返回当前生效的 Provider 名称及全部可选 Provider（便于前端渲染切换下拉）。
     */
    @Operation(summary = "当前引擎", description = "返回当前生效的 TTS Provider 名称（active）与全部可选 Provider（available）。")
    @GetMapping("/provider")
    public Result<Map<String, Object>> provider() {
        return Result.ok(buildProviderInfo());
    }

    /**
     * 运行时切换当前生效的 Provider（无需重启服务）。
     * 请求体示例：{ "provider": "mock" }
     */
    @Operation(summary = "切换引擎", description = "运行时切换当前生效的 TTS Provider（mock / edge-tts 等），返回切换后的 active 与 available。")
    @PostMapping("/provider")
    public Result<Map<String, Object>> setProvider(@RequestBody Map<String, String> body) {
        String name = body == null ? null : body.get("provider");
        ttsService.setActiveProvider(name);
        return Result.ok(buildProviderInfo());
    }

    /**
     * 全引擎连通性自检：逐个探测已注册 Provider 是否可用（如 edge-tts 能否取到令牌），
     * 并标注当前生效引擎。便于在用户环境快速定位「为何某引擎不出声」。
     */
    @Operation(summary = "连通性自检", description = "返回每个 TTS Provider 的可用性探测结果与当前生效引擎。")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(ttsService.health());
    }

    private Map<String, Object> buildProviderInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("active", ttsService.activeProvider());
        data.put("available", ttsService.availableProviders());
        return data;
    }
}
