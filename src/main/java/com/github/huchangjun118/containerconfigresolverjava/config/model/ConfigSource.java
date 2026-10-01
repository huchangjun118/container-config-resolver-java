package com.github.huchangjun118.containerconfigresolverjava.config.model;

import java.util.Map;

/**
 * 配置来源：带优先级的配置片段。
 *
 * @param id       来源唯一标识，用于追溯
 * @param name     来源展示名
 * @param priority 优先级
 * @param fragment 该来源提供的配置片段（字段为 null 表示未指定）
 */
public record ConfigSource(String id, String name, SourcePriority priority, ContainerConfig fragment) {

    public ConfigSource {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("source id must not be blank");
        }
        if (priority == null) {
            throw new IllegalArgumentException("source priority must not be null");
        }
        if (fragment == null) {
            throw new IllegalArgumentException("source fragment must not be null");
        }
    }
}
