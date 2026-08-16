package com.lengbot.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 缓存失效广播器（Redis Pub/Sub）
 * <p>多实例场景下，单实例清理本地缓存后通过 Redis 广播，所有订阅实例同步清理本地缓存，
 * 解决 ModelFactory / McpClientServiceImpl 等本地 Map 缓存在水平扩展时的数据不一致问题。</p>
 * <p>消息体格式：JSON {@code {"type":"chatModel","key":"123"}}，type 区分缓存域，key 为缓存键</p>
 *
 * @author finch
 * @since 2026-07-19
 */
@Slf4j
@Component
public class CacheInvalidationBroadcaster implements MessageListener {

    /** 广播频道名 */
    public static final String CHANNEL = "lengbot:cache:invalidate";

    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper;

    /** 失效处理器列表，按 type 路由；CopyOnWrite 保证订阅期间的并发读安全 */
    private final List<HandlerRegistration> handlers = new CopyOnWriteArrayList<>();

    public CacheInvalidationBroadcaster(RedisUtil redisUtil,
                                        ObjectMapper objectMapper,
                                        RedisMessageListenerContainer listenerContainer) {
        this.redisUtil = redisUtil;
        this.objectMapper = objectMapper;
        // 订阅失效频道：本实例收到其他实例的广播时，本地逐个 handler 调用清理
        listenerContainer.addMessageListener(this, new PatternTopic(CHANNEL));
    }

    /**
     * 注册失效处理器
     *
     * @param type    缓存域标识（如 "chatModel" / "mcpClient"）
     * @param handler 处理器（key 为 null 表示清空全部）
     */
    public void register(String type, CacheInvalidationHandler handler) {
        handlers.add(new HandlerRegistration(type, handler));
    }

    /**
     * 广播缓存失效（本地清理后调用，使其他实例同步清理）
     *
     * @param type 缓存域标识
     * @param key  缓存键（null 表示全部失效）
     */
    public void broadcast(String type, String key) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", type);
            payload.put("key", key);
            redisUtil.convertAndSend(CHANNEL, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("[CacheInvalidation] 广播失败 type={}, key={}: {}", type, key, e.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(body, Map.class);
            String type = String.valueOf(payload.get("type"));
            Object keyRaw = payload.get("key");
            String key = keyRaw == null ? null : String.valueOf(keyRaw);
            // 仅向同 type 的 handler 派发，避免无关 handler 被频繁唤醒
            for (HandlerRegistration reg : handlers) {
                if (reg.type.equals(type)) {
                    reg.handler.invalidate(key);
                }
            }
            log.debug("[CacheInvalidation] 收到广播 type={}, key={}", type, key);
        } catch (Exception e) {
            log.warn("[CacheInvalidation] 消息处理失败: {}", e.getMessage());
        }
    }

    /** 单个 handler 与其 type 的绑定 */
    private record HandlerRegistration(String type, CacheInvalidationHandler handler) {}
}
