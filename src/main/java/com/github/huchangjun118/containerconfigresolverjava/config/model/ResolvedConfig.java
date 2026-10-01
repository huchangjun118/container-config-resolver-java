package com.github.huchangjun118.containerconfigresolverjava.config.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 已提交的完整运行配置版本。不可变，读取线程安全。
 *
 * @param version    单调递增的版本号
 * @param config     合并、插值后的最终配置
 * @param provenance 每个最终字段的来源追溯（key 为字段路径）
 * @param sources    参与本次合并的来源 id（按优先级排序）
 * @param committedAt 提交时间戳
 */
public record ResolvedConfig(long version,
                             ContainerConfig config,
                             Map<String, Provenance> provenance,
                             List<String> sources,
                             Instant committedAt) {
}
