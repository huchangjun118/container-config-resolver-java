package com.github.huchangjun118.containerconfigresolverjava.model;

import java.util.List;

/**
 * 镜像自带的配置（最低优先级来源 IMAGE）。
 *
 * @param image        镜像引用，例如 nginx:1.27
 * @param os           镜像操作系统，例如 linux
 * @param architecture 镜像架构，例如 amd64
 * @param entrypoint   镜像 ENTRYPOINT（null 表示镜像未定义）
 * @param command      镜像 CMD（null 表示镜像未定义）
 */
public record ImageMetadata(
        String image,
        String os,
        String architecture,
        List<String> entrypoint,
        List<String> command
) {
    public ImageMetadata {
        entrypoint = entrypoint == null ? List.of() : List.copyOf(entrypoint);
        command = command == null ? List.of() : List.copyOf(command);
    }

    public static ImageMetadata empty() {
        return new ImageMetadata(null, null, null, List.of(), List.of());
    }
}
