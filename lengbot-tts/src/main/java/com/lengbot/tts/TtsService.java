package com.lengbot.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TTS 业务门面：屏蔽 Provider 选择细节，对外提供「合成 / 列音色 / 查当前引擎 / 切换引擎」能力。
 * <p>
 * 负责把请求中的 null 字段用 {@link TtsProperties} 默认值补齐，再委派给
 * {@link TtsProviderFactory} 选中的 Provider。当前生效的 Provider 可在运行时切换
 * （{@link #setActiveProvider(String)}），初始值取自配置 {@code lengbot.tts.provider}。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {

    private final TtsProviderFactory providerFactory;
    private final TtsProperties properties;
    private final ObjectMapper objectMapper;

    /** 当前生效的 Provider 名称（运行时可切换，初始取自配置）。volatile 保证切换的可见性。 */
    private volatile String currentProviderName;

    @PostConstruct
    void init() {
        currentProviderName = properties.getProvider();
    }

    /**
     * 合成音频。
     *
     * @param request 请求（文本必填，其余字段可空）
     * @return 音频结果
     */
    public TtsAudio synthesize(TtsRequest request) {
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            throw new TtsException("TTS 文本为空");
        }
        int max = Math.max(1, properties.getMaxTextLength());
        if (request.getText().length() > max) {
            throw new TtsException("TTS 文本超出最大长度限制(" + max + ")");
        }
        // 用默认值补齐 voice/rate/pitch/format（仅当为 null 时）
        TtsRequest effective = TtsRequest.builder()
                .text(request.getText())
                .voice(orElse(request.getVoice(), properties.getDefaultVoice()))
                .rate(orElse(request.getRate(), properties.getDefaultRate()))
                .pitch(orElse(request.getPitch(), properties.getDefaultPitch()))
                .format(orElse(request.getFormat(), properties.getFormat()))
                .build();
        // 优先使用请求级指定的 Provider（覆盖全局生效引擎），否则用当前生效引擎
        String providerName = (request.getProvider() != null && !request.getProvider().isBlank())
                ? request.getProvider()
                : currentProviderName;
        TtsProvider provider = providerFactory.getProvider(providerName);
        log.debug("[TtsService] 使用 Provider={} 合成文本(长度={})", provider.name(), request.getText().length());
        return provider.synthesize(effective);
    }

    /** 列出当前 Provider 支持的全部音色。 */
    public List<TtsVoice> listVoices() {
        return listVoices(currentProviderName);
    }

    /**
     * 列出指定 Provider 支持的全部音色（供音色管理同步使用）。
     *
     * @param providerName 目标 Provider 名称（为空则用当前生效引擎）
     */
    public List<TtsVoice> listVoices(String providerName) {
        String name = (providerName == null || providerName.isBlank())
                ? currentProviderName
                : providerName;
        return providerFactory.getProvider(name).listVoices();
    }

    /** 当前生效的 Provider 名称。 */
    public String activeProvider() {
        return currentProviderName;
    }

    /** 全部已注册的 Provider 名称（用于前端切换下拉）。 */
    public List<String> availableProviders() {
        return providerFactory.getProviderNames();
    }

    /**
     * 运行时切换当前生效的 Provider（无需重启服务）。
     *
     * @param name 目标 Provider 名称（必须已注册）
     * @throws TtsException 名称为空或不存在
     */
    public void setActiveProvider(String name) {
        if (name == null || name.isBlank()) {
            throw new TtsException("TTS Provider 名称不能为空");
        }
        if (!providerFactory.isRegistered(name)) {
            throw new TtsException("未注册的 TTS Provider: " + name + "，可选: " + providerFactory.getProviderNames());
        }
        currentProviderName = name;
        log.info("[TtsService] 运行时切换 TTS Provider 为 {}", name);
    }

    private static String orElse(String v, String d) {
        return (v == null || v.isBlank()) ? d : v;
    }

    private static Object orElse(Object v, Object d) {
        return v == null ? d : v;
    }

    /**
     * 全引擎连通性自检：逐个探测已注册 Provider 的可用性，并标注当前生效引擎。
     * 用于运维/前端在用户环境快速定位「为何某引擎不出声」。
     */
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, TtsProviderHealth> providers = new LinkedHashMap<>();
        for (TtsProvider p : providerFactory.getProviders()) {
            providers.put(p.name(), p.health());
        }
        result.put("active", currentProviderName);
        result.put("providers", providers);
        return result;
    }
}
