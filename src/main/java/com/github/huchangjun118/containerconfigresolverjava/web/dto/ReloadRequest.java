package com.github.huchangjun118.containerconfigresolverjava.web.dto;

import com.github.huchangjun118.containerconfigresolverjava.model.ImageMetadata;
import com.github.huchangjun118.containerconfigresolverjava.model.RawContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.model.SourceLayer;

import java.util.List;
import java.util.Map;

/**
 * 手动重载请求体：一个完整来源版本 + 外部变量取值。
 */
public record ReloadRequest(
        String revision,
        ImageMetadata image,
        List<LayerDto> layers,
        Map<String, String> externals
) {
    public ReloadRequest {
        layers = layers == null ? List.of() : List.copyOf(layers);
        externals = externals == null ? Map.of() : Map.copyOf(externals);
    }

    /** 单层覆盖来源。 */
    public record LayerDto(SourceLayer layer, String name, RawContainerConfig config) {
    }
}
