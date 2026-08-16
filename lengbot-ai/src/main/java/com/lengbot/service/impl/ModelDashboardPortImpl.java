package com.lengbot.service.impl;

import com.lengbot.mapper.ModelMapper;
import com.lengbot.mapper.ModelProviderMapper;
import com.lengbot.service.port.ModelDashboardPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 模型域 Dashboard 统计实现。
 */
@Service
@RequiredArgsConstructor
public class ModelDashboardPortImpl implements ModelDashboardPort {

    private final ModelProviderMapper modelProviderMapper;
    private final ModelMapper modelMapper;

    @Override
    public long countProviders() {
        return modelProviderMapper.selectCount(null);
    }

    @Override
    public long countModels() {
        return modelMapper.selectCount(null);
    }
}
