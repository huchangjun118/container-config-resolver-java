package com.github.huchangjun118.containerconfigresolverjava.config.model;

/**
 * 字段取值来源：sourceId + 字段在该来源内的路径。
 */
public record Provenance(String sourceId, String path) {
}
