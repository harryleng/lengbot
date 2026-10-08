package com.lengbot.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.entity.ModelProvider;
import com.lengbot.enums.ModelProviderType;
import com.lengbot.service.ModelProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * 知识库入库 VLM 图示增强：把 PDF 中「含内嵌图或整页无文字」的页、以及上传图片渲染后，
 * 送 MiMo 多模态（OpenAI 兼容 /chat/completions）理解，生成关系图/流程图/图表/表格的文字描述，
 * 补 Tika 文本抽取与 RapidOCR 丢失的视觉结构语义。
 * <p>直接复用已配置的 MiMo 多模态 Provider（com.lengbot.service.ModelProviderService 取 MIMO 类型），
 * 不依赖 agent 模块的 MimoChatClient，避免跨模块循环依赖。</p>
 *
 * @author lw
 * @since 2026-10-08 (TOC added)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionEnrichUtil {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;

    private static final int MAX_PAGES = 40;
    private static final int MAX_VLM_PAGES = 20;
    private static final int RENDER_DPI = 150;
    private static final String DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1";
    private static final String MODEL = "mimo-v2.5-pro";

    private static final String VLM_PROMPT = "你是文档视觉理解助手。请详细描述这张图片中的可视化内容："
            + "关系图/流程图需说明实体节点与连线关系；图表需说明坐标轴、图例、数据趋势与关键数值；"
            + "表格需说明行列结构与关键单元格。若图片只是纯正文文字或没有任何图示/图表，请只回复 NONE。";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * PDF 图示增强：渲染「含内嵌图或整页无文字」的页送 VLM，返回拼装好的描述文本（无图则为 null）。
     */
    public String enrichPdf(byte[] pdfBytes) {
        ModelProvider provider = resolveMimoProvider();
        if (provider == null) {
            log.warn("[Vision] 无可用 MiMo 多模态 Provider，跳过图示增强");
            return null;
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int total = doc.getNumberOfPages();
            int limit = Math.min(total, MAX_PAGES);
            PDFRenderer renderer = new PDFRenderer(doc);
            PDFTextStripper stripper = new PDFTextStripper();
            StringBuilder sb = new StringBuilder();
            int vlmCalls = 0;
            for (int i = 0; i < limit; i++) {
                PDPage page = doc.getPage(i);
                boolean visual = hasEmbeddedImage(page);
                if (!visual) {
                    stripper.setStartPage(i + 1);
                    stripper.setEndPage(i + 1);
                    String pt = stripper.getText(doc);
                    if (pt == null || pt.replaceAll("\\s+", "").length() < 10) {
                        visual = true;
                    }
                }
                if (!visual || vlmCalls >= MAX_VLM_PAGES) {
                    continue;
                }
                BufferedImage img = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
                byte[] png = toPng(img);
                String desc = describe(provider, png, "image/png");
                if (isUseful(desc)) {
                    sb.append("\n\n## 图示说明（第").append(i + 1).append("页）\n\n").append(desc.trim());
                }
                vlmCalls++;
            }
            log.info("[Vision] PDF 图示增强完成(非扫描): 总页数={}, 渲染上限={}, VLM调用={}/{}, 是否有图示描述={}",
                    total, limit, vlmCalls, MAX_VLM_PAGES, sb.length() > 0);
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("[Vision] PDF 图示增强失败: {}", e.getMessage());
            return null;
        }
    }
    /**
     * PDF 图示增强（逐页对齐版）：返回按页码对齐的结果列表（索引 = 页码-1）。
     * 仅对「含内嵌图或整页无文字」的页调用 VLM，空白/无图示页占位为空串，便于与 OCR 结果逐页插值。
     *
     * @param pdfBytes PDF 字节
     * @return 每页视觉描述列表（长度 = min(总页数, MAX_PAGES)）
     */
    public List<String> enrichPdfPages(byte[] pdfBytes) {
        ModelProvider provider = resolveMimoProvider();
        List<String> pages = new ArrayList<>();
        if (provider == null) {
            log.warn("[Vision] 无可用 MiMo 多模态 Provider，跳过逐页图示增强");
            return pages;
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int total = doc.getNumberOfPages();
            int limit = Math.min(total, MAX_PAGES);
            PDFRenderer renderer = new PDFRenderer(doc);
            PDFTextStripper stripper = new PDFTextStripper();
            int vlmCalls = 0;
            for (int i = 0; i < limit; i++) {
                PDPage page = doc.getPage(i);
                boolean visual = hasEmbeddedImage(page);
                if (!visual) {
                    stripper.setStartPage(i + 1);
                    stripper.setEndPage(i + 1);
                    String pt = stripper.getText(doc);
                    if (pt == null || pt.replaceAll("\\s+", "").length() < 10) {
                        visual = true;
                    }
                }
                String desc = "";
                if (visual && vlmCalls < MAX_VLM_PAGES) {
                    BufferedImage img = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
                    byte[] png = toPng(img);
                    String d = describe(provider, png, "image/png");
                    if (isUseful(d)) {
                        desc = d.trim();
                    }
                    vlmCalls++;
                }
                pages.add(desc);
            }
            int useful = 0;
            for (String p : pages) {
                if (p != null && !p.isEmpty()) {
                    useful++;
                }
            }
            log.info("[Vision] 扫描件逐页图示增强完成: 总页数={}, 渲染上限={}, VLM调用={}/{}, 有效描述={}",
                    total, limit, vlmCalls, MAX_VLM_PAGES, useful);
            if (vlmCalls >= MAX_VLM_PAGES && limit > MAX_VLM_PAGES) {
                log.warn("[Vision] 扫描件 PDF 超出 VLM 单文档上限({}页)，第 {} 页起仅走 OCR 不调多模态",
                        MAX_VLM_PAGES, MAX_VLM_PAGES + 1);
            }
        } catch (Exception e) {
            log.warn("[Vision] PDF 逐页图示增强失败: {}", e.getMessage());
        }
        return pages;
    }

    /**
     * 单张上传图片图示增强。fileType 形如 pdf/jpg/png，用于推导 mime。
     */
    public String enrichImage(byte[] imageBytes, String fileType) {
        ModelProvider provider = resolveMimoProvider();
        if (provider == null) {
            return null;
        }
        String desc = describe(provider, imageBytes, deriveMime(fileType));
        boolean ok = isUseful(desc);
        log.info("[Vision] 单图图示增强完成: fileType={}, 是否产出描述={}", fileType, ok);
        return ok ? desc : null;
    }

    /**
     * PDF 大纲/书签提取：还原层级化目录（Tika 平铺会丢失层级）。
     * 返回 Markdown 层级标题（## / ### ...），无大纲返回 null。
     */
    public String extractToc(byte[] pdfBytes) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("## 文档目录\n\n");
            buildOutline(outline, sb, 2);
            String toc = sb.toString().trim();
            return toc.isEmpty() ? null : toc;
        } catch (Exception e) {
            log.warn("[Vision] PDF 大纲提取失败: {}", e.getMessage());
            return null;
        }
    }

    private void buildOutline(PDOutlineNode node, StringBuilder sb, int level) {
        if (node == null) {
            return;
        }
        for (PDOutlineItem item : node.children()) {
            String title = item.getTitle();
            if (title == null || title.isBlank()) {
                continue;
            }
            int h = Math.min(level, 6);
            for (int i = 0; i < h; i++) {
                sb.append('#');
            }
            sb.append(' ').append(title.trim()).append("\n\n");
            if (item.hasChildren()) {
                buildOutline(item, sb, level + 1);
            }
        }
    }

    private boolean hasEmbeddedImage(PDPage page) {
        try {
            PDResources res = page.getResources();
            if (res == null) {
                return false;
            }
            for (COSName name : res.getXObjectNames()) {
                if (res.getXObject(name) instanceof PDImageXObject) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 资源解析失败按无图处理
        }
        return false;
    }

    private byte[] toPng(BufferedImage img) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private String describe(ModelProvider provider, byte[] imageBytes, String mimeType) {
        try {
            String dataUrl = "data:" + (mimeType != null ? mimeType : "image/png")
                    + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
            List<Object> userContent = new ArrayList<>();
            userContent.add(Map.of("type", "text", "text", VLM_PROMPT));
            userContent.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
            Map<String, Object> body = Map.of(
                    "model", MODEL,
                    "messages", List.of(Map.of("role", "user", "content", userContent)),
                    "stream", false,
                    "thinking", Map.of("type", "disabled"));
            String json = objectMapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(normalizeBaseUrl(provider.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + provider.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[Vision] MiMo 返回 HTTP {}: {}", resp.statusCode(), resp.body());
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode content = choices.get(0).get("message").get("content");
                if (content != null) {
                    if (content.isTextual()) {
                        return content.asText();
                    }
                    if (content.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode p : content) {
                            if (p.has("text")) {
                                sb.append(p.get("text").asText());
                            }
                        }
                        return sb.toString();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Vision] MiMo 多模态调用失败: {}", e.getMessage());
        }
        return null;
    }

    private ModelProvider resolveMimoProvider() {
        try {
            return modelProviderService.listAllActive().stream()
                    .filter(p -> p.getType() == ModelProviderType.MIMO)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        baseUrl = baseUrl.replaceAll("/+$", "");
        if (!baseUrl.endsWith("/v1")) {
            baseUrl = baseUrl + "/v1";
        }
        return baseUrl;
    }

    private String deriveMime(String fileType) {
        if (fileType == null) {
            return "image/png";
        }
        return switch (fileType.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "bmp" -> "image/bmp";
            case "tif", "tiff" -> "image/tiff";
            default -> "image/png";
        };
    }

    private boolean isUseful(String desc) {
        if (desc == null || desc.isBlank()) {
            return false;
        }
        String t = desc.trim();
        return !"NONE".equalsIgnoreCase(t) && !"无".equals(t) && !"无图示".equals(t);
    }
}
