package com.github.huchangjun118.containerconfigresolverjava.config.model;

/**
 * 卷挂载：source 为卷来源，target 为容器内挂载点，readOnly 为只读挂载。
 */
public record VolumeMount(String source, String target, boolean readOnly) {
}
