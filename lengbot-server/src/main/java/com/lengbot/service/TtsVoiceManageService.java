package com.lengbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lengbot.common.BizException;
import com.lengbot.entity.TtsVoiceEntity;
import com.lengbot.enums.ErrorCode;
import com.lengbot.mapper.TtsVoiceMapper;
import com.lengbot.tts.TtsService;
import com.lengbot.tts.TtsVoice;
import com.lengbot.vo.TtsVoiceView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * TTS 多音色管理：缓存 Provider 音色清单并叠加本地管理字段（收藏 / 分组 / 备注）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>列表优先读本地缓存，离线可用；首次为空时尝试从 Provider 懒同步一次。</li>
 *   <li>同步采用 upsert：仅刷新 Provider 派生字段（展示名/语言/性别），保留用户的收藏/分组/备注。</li>
 *   <li>元数据更新为局部更新（只改传入的字段），不影响其它字段。</li>
 * </ul>
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtsVoiceManageService {

    private final TtsVoiceMapper mapper;
    private final TtsService ttsService;

    /**
     * 查询受管音色列表（带筛选）。
     *
     * @param provider 引擎过滤（可空）
     * @param locale   语言区域过滤（可空）
     * @param gender   性别过滤（可空）
     * @param favorite 仅收藏（可空；true 时只看收藏）
     * @param group    分组过滤（可空）
     * @param keyword  关键字（匹配音色名或展示名，可空）
     * @return 视图列表（收藏优先、排序权重升序、ID 升序）
     */
    public List<TtsVoiceView> list(String provider, String locale, String gender,
                                   Boolean favorite, String group, String keyword) {
        LambdaQueryWrapper<TtsVoiceEntity> q = buildQuery(provider, locale, gender, favorite, group, keyword);
        List<TtsVoiceEntity> list;
        try {
            list = mapper.selectList(q);
        } catch (Exception e) {
            // 表尚未创建（未执行迁移）时，回退到实时拉取，避免影响现有音色下拉
            log.warn("[TtsVoice] 本地表查询失败，回退实时列表: {}", e.getMessage());
            return liveFallback(provider);
        }

        // 空库兜底：尝试从当前 Provider 同步一次，避免全新部署时列表为空
        if (list.isEmpty()) {
            try {
                int synced = syncFromProvider(null);
                if (synced > 0) {
                    list = mapper.selectList(q);
                }
            } catch (Exception e) {
                log.warn("[TtsVoice] 空库懒同步失败，回退实时列表: {}", e.getMessage());
                return liveFallback(provider);
            }
        }
        if (list.isEmpty()) {
            return liveFallback(provider);
        }
        return list.stream().map(this::toView).collect(Collectors.toList());
    }

    /**
     * 实时回退：直接拉取 Provider 音色并转为视图（无收藏/分组/备注），
     * 用于本地表缺失或空库且同步失败时的兼容场景，保证现有音色下拉不中断。
     */
    private List<TtsVoiceView> liveFallback(String providerName) {
        try {
            List<TtsVoice> live = ttsService.listVoices(providerName);
            if (live == null || live.isEmpty()) {
                return List.of();
            }
            return live.stream().map(v -> {
                TtsVoiceView view = new TtsVoiceView();
                view.setVoiceURI(v.getName());
                view.setName(v.getName());
                view.setFriendlyName(v.getFriendlyName());
                view.setLocale(v.getLocale());
                view.setGender(v.getGender());
                view.setProvider(v.getProvider());
                view.setFavorite(false);
                view.setVoiceGroup("");
                view.setRemark("");
                view.setSortOrder(0);
                return view;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[TtsVoice] 实时回退也失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从指定 Provider（为空则用当前生效引擎）拉取音色并 upsert 到本地。
     *
     * @param providerName 目标 Provider（可空）
     * @return 新增或发生变更的音色数量
     */
    public int syncFromProvider(String providerName) {
        List<TtsVoice> live = ttsService.listVoices(providerName);
        if (live == null || live.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TtsVoice v : live) {
            if (v == null || !StringUtils.hasText(v.getName())) {
                continue;
            }
            String provider = StringUtils.hasText(v.getProvider()) ? v.getProvider() : "edge-tts";
            LambdaQueryWrapper<TtsVoiceEntity> q = Wrappers.lambdaQuery();
            q.eq(TtsVoiceEntity::getProvider, provider).eq(TtsVoiceEntity::getVoiceName, v.getName());
            TtsVoiceEntity exist = mapper.selectOne(q);

            if (exist == null) {
                TtsVoiceEntity e = new TtsVoiceEntity();
                e.setVoiceName(v.getName());
                e.setProvider(provider);
                e.setFriendlyName(v.getFriendlyName());
                e.setLocale(v.getLocale());
                e.setGender(v.getGender());
                e.setFavorite(0);
                e.setVoiceGroup("");
                e.setRemark("");
                e.setSortOrder(0);
                mapper.insert(e);
                count++;
            } else {
                // 仅更新 Provider 派生字段，保留收藏/分组/备注
                boolean changed = false;
                if (!Objects.equals(exist.getFriendlyName(), v.getFriendlyName())) {
                    exist.setFriendlyName(v.getFriendlyName());
                    changed = true;
                }
                if (!Objects.equals(exist.getLocale(), v.getLocale())) {
                    exist.setLocale(v.getLocale());
                    changed = true;
                }
                if (!Objects.equals(exist.getGender(), v.getGender())) {
                    exist.setGender(v.getGender());
                    changed = true;
                }
                if (changed) {
                    mapper.updateById(exist);
                    count++;
                }
            }
        }
        log.info("[TtsVoice] 同步完成：Provider={}, 变更/新增={} 条", providerName, count);
        return count;
    }

    /**
     * 局部更新某音色的元数据（收藏 / 分组 / 备注）。只处理 body 中出现的字段。
     *
     * @param voiceName 音色名
     * @param provider  所属引擎（可空，用于精确定位）
     * @param body      待更新字段（favorite / voiceGroup / remark）
     */
    public void updateMeta(String voiceName, String provider, Map<String, Object> body) {
        if (!StringUtils.hasText(voiceName)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "音色名不能为空");
        }
        LambdaQueryWrapper<TtsVoiceEntity> q = Wrappers.lambdaQuery();
        q.eq(TtsVoiceEntity::getVoiceName, voiceName);
        if (StringUtils.hasText(provider)) {
            q.eq(TtsVoiceEntity::getProvider, provider);
        }
        TtsVoiceEntity e = mapper.selectOne(q);
        if (e == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "音色不存在: " + voiceName);
        }
        if (body == null) {
            return;
        }
        if (body.containsKey("favorite")) {
            Object f = body.get("favorite");
            boolean fav = f instanceof Boolean ? (Boolean) f : Boolean.parseBoolean(String.valueOf(f));
            e.setFavorite(fav ? 1 : 0);
        }
        if (body.containsKey("voiceGroup")) {
            Object g = body.get("voiceGroup");
            e.setVoiceGroup(g == null ? "" : g.toString().trim());
        }
        if (body.containsKey("remark")) {
            Object r = body.get("remark");
            e.setRemark(r == null ? "" : r.toString());
        }
        mapper.updateById(e);
    }

    /**
     * 列出所有被使用的分组（去重、去空、排序），用于前端筛选与下拉。
     */
    public List<String> listGroups() {
        LambdaQueryWrapper<TtsVoiceEntity> q = Wrappers.lambdaQuery();
        q.select(TtsVoiceEntity::getVoiceGroup)
                .isNotNull(TtsVoiceEntity::getVoiceGroup)
                .ne(TtsVoiceEntity::getVoiceGroup, "");
        List<TtsVoiceEntity> list = mapper.selectList(q);
        return list.stream()
                .map(TtsVoiceEntity::getVoiceGroup)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private LambdaQueryWrapper<TtsVoiceEntity> buildQuery(String provider, String locale, String gender,
                                                          Boolean favorite, String group, String keyword) {
        LambdaQueryWrapper<TtsVoiceEntity> q = Wrappers.lambdaQuery();
        if (StringUtils.hasText(provider)) {
            q.eq(TtsVoiceEntity::getProvider, provider);
        }
        if (StringUtils.hasText(locale)) {
            q.eq(TtsVoiceEntity::getLocale, locale);
        }
        if (StringUtils.hasText(gender)) {
            q.eq(TtsVoiceEntity::getGender, gender);
        }
        if (favorite != null && favorite) {
            q.eq(TtsVoiceEntity::getFavorite, 1);
        }
        if (StringUtils.hasText(group)) {
            q.eq(TtsVoiceEntity::getVoiceGroup, group);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            q.and(w -> w.like(TtsVoiceEntity::getVoiceName, kw).or().like(TtsVoiceEntity::getFriendlyName, kw));
        }
        q.orderByDesc(TtsVoiceEntity::getFavorite)
                .orderByAsc(TtsVoiceEntity::getSortOrder)
                .orderByAsc(TtsVoiceEntity::getId);
        return q;
    }

    private TtsVoiceView toView(TtsVoiceEntity e) {
        TtsVoiceView v = new TtsVoiceView();
        v.setVoiceURI(e.getVoiceName());
        v.setName(e.getVoiceName());
        v.setFriendlyName(e.getFriendlyName());
        v.setLocale(e.getLocale());
        v.setGender(e.getGender());
        v.setProvider(e.getProvider());
        v.setFavorite(e.getFavorite() != null && e.getFavorite() == 1);
        v.setVoiceGroup(e.getVoiceGroup());
        v.setRemark(e.getRemark());
        v.setSortOrder(e.getSortOrder());
        return v;
    }
}
