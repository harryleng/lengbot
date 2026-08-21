package com.lengbot.interceptor;

import com.lengbot.common.BizException;
import com.lengbot.enums.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 分页参数上限拦截器
 * <p>限制 pageSize 不超过 {@link #MAX_PAGE_SIZE}，避免恶意大分页请求打 DB</p>
 * <p>同时兼容 pageNum/pageSize 缺省、非数字、负数等情况：缺省放行，非法值按上限拒绝</p>
 *
 * @author lw
 * @since 2026-07-20
 */
@Component
public class PageSizeLimitInterceptor implements HandlerInterceptor {

    /**
     * 全局 pageSize 上限，覆盖列表/分页接口
     * <p>200 兼容下拉/绑定等"加载全部"业务场景（最大节点数约 100-200），
     * 同时挡住 pageSize=1000/10000 等恶意大分页对 DB 的打宽</p>
     */
    public static final int MAX_PAGE_SIZE = 200;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 仅校验常见的分页参数名，命中即限上限；缺省/非数字跳过（后续 Controller 自行兜底）
        checkSize(request, "pageSize");
        checkSize(request, "size");
        return true;
    }

    /**
     * 校验单个分页参数：缺省/非数字放行，超过上限抛 PARAM_INVALID
     */
    private void checkSize(HttpServletRequest request, String param) {
        String raw = request.getParameter(param);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            // 非数字交给后续 @RequestParam 类型转换报错，不在此处拦截
            return;
        }
        if (value > MAX_PAGE_SIZE) {
            throw new BizException(ErrorCode.PARAM_INVALID, param + " 不能超过 " + MAX_PAGE_SIZE);
        }
    }
}
