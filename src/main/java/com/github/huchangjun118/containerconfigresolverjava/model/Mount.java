package com.github.huchangjun118.containerconfigresolverjava.model;

import java.util.Objects;

/**
 * 卷/绑定挂载。
 *
 * @param source   主机路径或卷名（tmpfs 可为空）
 * @param target   容器内挂载点（绝对路径）
 * @param readOnly 是否只读
 * @param type     bind / volume / tmpfs
 */
public record Mount(String source, String target, boolean readOnly, String type) {

    public Mount {
        Objects.requireNonNull(target, "mount target");
        if (!target.startsWith("/")) {
            throw new IllegalArgumentException("mount target must be absolute: " + target);
        }
        type = type == null || type.isBlank() ? "bind" : type.toLowerCase();
    }
}
