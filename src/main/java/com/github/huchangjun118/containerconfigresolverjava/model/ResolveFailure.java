package com.github.huchangjun118.containerconfigresolverjava.model;

import java.time.Instant;

/**
 * 最近一次求值/重载失败的记录。失败只更新该记录，绝不污染已生效的快照。
 *
 * @param revision  失败版本的 revision（可空，表示尚无版本号）
 * @param phase     失败阶段：MERGE / INTERPOLATE / VALIDATE / SOURCE
 * @param message   原因摘要
 * @param path      相关字段路径（可空）
 * @param time      失败时间
 */
public record ResolveFailure(String revision, String phase, String message, String path, Instant time) {

    public static ResolveFailure of(String revision, String phase, Throwable error) {
        String path = error instanceof ConfigException ce ? ce.path() : null;
        return new ResolveFailure(revision, phase, String.valueOf(error.getMessage()), path, Instant.now());
    }
}
