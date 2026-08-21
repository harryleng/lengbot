package com.lengbot.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lengbot.service.sandbox.SkillStorageService;
import com.lengbot.tool.annotation.SystemTool;
import com.lengbot.tool.annotation.ToolParamMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置工具 — 列出技能文件
 * <p>列出指定 Skill 目录下的所有文件。</p>
 *
 * @author lw
 * @since 2026-06-18
 */
@Slf4j
@Component("listSkillFilesTool")
@RequiredArgsConstructor
@SystemTool(displayName = "列出技能文件", icon = "FolderOpenOutlined", description = "列出技能目录下的所有文件", tags = {"技能"},
        outputExample = "{\"slug\":\"deep-research\",\"files\":[\"SKILL.md\",\"README.md\",\"config.json\"],\"total\":3}",
        outputSchema = "{\"type\":\"object\",\"properties\":{\"slug\":{\"type\":\"string\",\"description\":\"技能slug标识\"},\"files\":{\"type\":\"array\",\"description\":\"文件名列表\",\"items\":{\"type\":\"string\"}},\"total\":{\"type\":\"integer\",\"description\":\"文件总数\"}}}")
public class ListSkillFilesTool {

    private final SkillStorageService skillStorageService;
    private final ObjectMapper objectMapper;

    @Tool(name = "list_skill_files",
          description = "列出指定技能目录下的所有文件。传入技能的 slug 标识，返回该技能目录下的文件列表。")
    public String listFiles(
            @ToolParam(name = "slug", description = "技能的 slug 标识")
            @ToolParamMeta(example = "deep-research") String slug) {
        log.info("[Tool:list_skill_files] 列出文件: slug={}", slug);

        if (slug == null || slug.isBlank()) {
            return "错误：slug 不能为空";
        }

        String normalizedSlug = slug.trim();

        if (!skillStorageService.skillMarkdownExists(normalizedSlug)) {
            return "错误：未找到技能 " + normalizedSlug;
        }

        // 列出 MinIO 中 skills/{slug}/ 下的文件
        try {
            List<String> objects = com.lengbot.util.SandboxPathValidator.normalize("skills/" + normalizedSlug + "/")
                    .equals("skills/" + normalizedSlug + "/")
                    ? skillStorageService.listSkillFiles(normalizedSlug)
                    : List.of();
            if (objects.isEmpty()) {
                return "技能目录为空";
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("slug", normalizedSlug);
            output.put("files", objects);
            output.put("total", objects.size());
            return objectMapper.writeValueAsString(output);
        } catch (Exception e) {
            return "错误：列举文件失败 - " + e.getMessage();
        }
    }
}
