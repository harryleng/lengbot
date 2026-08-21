package com.lengbot.event;

/**
 * 缓存失效处理器（多实例广播接收方实现）
 * <p>当本实例收到其他实例广播的失效消息时，按 type 路由调用对应 handler 清理本地缓存。</p>
 *
 * @author lw
 * @since 2026-07-19
 */
@FunctionalInterface
public interface CacheInvalidationHandler {

    /**
     * 清理本地缓存
     *
     * @param key 缓存键；为 null 表示清空该域全部缓存
     */
    void invalidate(String key);
}
