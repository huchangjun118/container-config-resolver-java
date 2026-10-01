package com.github.huchangjun118.containerconfigresolverjava.config.registry;

import com.github.huchangjun118.containerconfigresolverjava.config.model.ConfigSource;

import java.util.List;

/**
 * 来源提供者：每次重载时返回当前最新的来源列表。
 */
@FunctionalInterface
public interface SourceProvider {

    List<ConfigSource> load();
}
