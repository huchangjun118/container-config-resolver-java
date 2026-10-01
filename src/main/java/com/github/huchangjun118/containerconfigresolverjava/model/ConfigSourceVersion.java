package com.github.huchangjun118.containerconfigresolverjava.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一次重载所读取的、不可变的来源快照。调用方在单一时刻采集各来源，
 * 打包成这个整体提交给 resolver；resolver 永远只对"某一版完整来源"求值，
 * 读取结果只能是某次已提交的版本，不会出现字段级混合。
 */
public final class ConfigSourceVersion {

    private final String revision;
    private final ImageMetadata image;
    private final List<LayeredSource> layers;

    public ConfigSourceVersion(String revision, ImageMetadata image, List<LayeredSource> layers) {
        this.revision = revision;
        this.image = image == null ? ImageMetadata.empty() : image;
        this.layers = layers == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(layers));
    }

    public String revision() {
        return revision;
    }

    public ImageMetadata image() {
        return image;
    }

    /** 按优先级从低到高返回非镜像来源。 */
    public List<LayeredSource> layers() {
        return layers;
    }

    /**
     * 单个已命名的覆盖层。
     *
     * @param layer  层级
     * @param name   来源标识（模板名/环境名/服务名等），写入字段追溯信息
     * @param config 该来源提交的完整配置
     */
    public record LayeredSource(SourceLayer layer, String name, RawContainerConfig config) {
        public LayeredSource {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(config, "config");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("source name must not be blank");
            }
        }
    }
}
