package com.lengbot.service.digitalhuman;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 轻量占位版数字人引擎。
 *
 * <p>不生成真实视频，仅作为扩展点骨架：返回 {@code frontendDriven=true}，
 * 告知调用方由前端用「形象图 + 口型动画」驱动口型。后续接入商业 / 本地
 * 开源引擎时，实现 {@link DigitalHumanEngine} 并在 Spring 容器中注册为
 * 对应 {@code engine} 名称的 Bean 即可平滑替换。
 */
@Slf4j
@Component
public class PlaceholderDigitalHumanEngine implements DigitalHumanEngine {

    @Override
    public String name() {
        return "placeholder";
    }

    @Override
    public DigitalHumanResult generate(DigitalHumanRequest request) {
        DigitalHumanResult result = new DigitalHumanResult();
        result.setFrontendDriven(true);
        result.setVideoUrl(null);
        log.debug("[DigitalHuman] placeholder 引擎不生成视频，交由前端动画驱动；portrait={}", request.getPortraitUrl());
        return result;
    }
}
