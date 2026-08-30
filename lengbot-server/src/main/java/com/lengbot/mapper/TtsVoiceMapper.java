package com.lengbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lengbot.entity.TtsVoiceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * TTS 音色管理 Mapper。
 *
 * @author LengBot Team
 * @since 1.0.0
 */
@Mapper
public interface TtsVoiceMapper extends BaseMapper<TtsVoiceEntity> {
}
