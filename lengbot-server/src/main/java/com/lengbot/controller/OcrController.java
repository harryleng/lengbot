package com.lengbot.controller;

import com.lengbot.common.Result;
import com.lengbot.dto.OcrHealthResultDTO;
import com.lengbot.util.OcrUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OCR 服务接口
 *
 * @author lw
 * @since 2026-05-21
 */
@Tag(name = "OCR服务", description = "OCR健康检查和模型管理")
@RestController
@RequestMapping("/api/ocr")
@RequiredArgsConstructor
public class OcrController {

    private final OcrUtil ocrUtil;

    @Operation(summary = "OCR健康检查")
    @GetMapping("/health")
    public Result<OcrHealthResultDTO> healthCheck() {
        return Result.ok(ocrUtil.healthCheck());
    }
}
