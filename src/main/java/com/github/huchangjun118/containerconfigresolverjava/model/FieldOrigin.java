package com.github.huchangjun118.containerconfigresolverjava.model;

/**
 * 最终字段的可追溯信息。
 *
 * @param path   逻辑字段路径，例如 env.LOG_LEVEL / entrypoint[0] / mounts[2].target
 * @param layer  取值来自哪个来源层级
 * @param source 来源标识（模板名/环境名/服务名等），镜像层为镜像引用
 * @param detail 附加说明（例如 entrypoint 被覆盖导致 cmd 重置的解释），可为空
 */
public record FieldOrigin(String path, SourceLayer layer, String source, String detail) {

    public static FieldOrigin of(String path, SourceLayer layer, String source) {
        return new FieldOrigin(path, layer, source, null);
    }

    public FieldOrigin withDetail(String detail) {
        return new FieldOrigin(path, layer, source, detail);
    }
}
