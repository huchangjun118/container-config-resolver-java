package com.github.huchangjun118.containerconfigresolverjava.model;

import java.util.Objects;

import com.github.huchangjun118.containerconfigresolverjava.validation.ValidationResult;

/**
 * 一次成功求值后提交的自洽快照。整个对象不可变并安全发布（volatile 引用），
 * 读取方拿到的永远是一个完整版本，慢读取不会阻塞后续重载。
 *
 * @param revision         来源版本号
 * @param versionId        由最终字段确定性计算出的版本 id（跨进程一致）
 * @param resolved         插值后的最终运行定义
 * @param interpolatedFrom 插值前（合并后）的运行定义，保留来源追溯
 * @param validationResult 校验结论（valid=true 才允许提交）
 */
public record CommittedSnapshot(
        String revision,
        String versionId,
        ResolvedContainerConfig resolved,
        ResolvedContainerConfig interpolatedFrom,
        ValidationResult validationResult
) {
    public CommittedSnapshot {
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(validationResult, "validationResult");
        if (!validationResult.valid()) {
            throw new IllegalArgumentException("cannot commit invalid snapshot");
        }
    }
}
