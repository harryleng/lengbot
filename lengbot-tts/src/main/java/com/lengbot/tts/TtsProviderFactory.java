package com.lengbot.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TTS Provider 工厂 —— 收集所有 {@link TtsProvider} 实现，按 {@code name()} 建索引。
 * <p>
 * 复用 lengbot-ai 中 {@code ModelFactory} 的「Spring 自动注入 List + 按类型分发」范式。
 * 新增引擎只需新增一个 {@code @Component} 实现 {@link TtsProvider}，无需改动本类。
 * </p>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class TtsProviderFactory {

    private final Map<String, TtsProvider> providerMap = new ConcurrentHashMap<>();
    private final List<TtsProvider> providers;

    public TtsProviderFactory(List<TtsProvider> providers) {
        this.providers = providers;
        for (TtsProvider p : providers) {
            providerMap.put(p.name(), p);
        }
        log.info("[TtsProviderFactory] 已注册 {} 个 TTS Provider: {}", providerMap.size(), providerMap.keySet());
    }

    /**
     * 按名称取 Provider；名称为空或不存在时，回退到第一个可用的 Provider。
     *
     * @param name 配置中的 provider 名称（如 edge-tts / mock）
     * @return 命中的 Provider（永不返回 null）
     * @throws TtsException 无任何可用 Provider
     */
    public TtsProvider getProvider(String name) {
        if (name != null && !name.isBlank()) {
            TtsProvider p = providerMap.get(name);
            if (p != null) {
                return p;
            }
            log.warn("[TtsProviderFactory] 未找到名为 '{}' 的 TTS Provider，回退到默认", name);
        }
        for (TtsProvider p : providers) {
            if (p.isAvailable()) {
                return p;
            }
        }
        if (!providers.isEmpty()) {
            return providers.get(0);
        }
        throw new TtsException("无任何已注册的 TTS Provider");
    }

    /** 已注册的全部 Provider 名称（用于前端切换下拉）。 */
    public List<String> getProviderNames() {
        return new ArrayList<>(providerMap.keySet());
    }

    /** 已注册的全部 Provider 实例（用于逐引擎健康自检）。 */
    public List<TtsProvider> getProviders() {
        return new ArrayList<>(providers);
    }

    /** 判断指定名称的 Provider 是否已注册。 */
    public boolean isRegistered(String name) {
        return name != null && providerMap.containsKey(name);
    }
}
