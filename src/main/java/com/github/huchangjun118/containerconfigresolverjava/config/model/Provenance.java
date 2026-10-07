package com.github.huchangjun118.containerconfigresolverjava.config.model;

/**
 * 字段取值来源：sourceId + 字段在该来源内的路径 + 生效方式。
 *
 * <p>{@link Kind#RESET} 用于标记"被重置"的字段（例如某层只给出入口点导致命令被重置），
 * 与"恰好被覆盖成空值"（{@link Kind#SET} 且值为空集合）明确区分。
 */
public record Provenance(String sourceId, String path, Kind kind) {

    /** 生效方式。 */
    public enum Kind {
        /** 该来源显式给字段赋了值（包括显式赋空集合）。 */
        SET,
        /** 字段被重置：值不是来自任何来源的显式赋值，而是合并规则产生的重置结果。 */
        RESET
    }

    /** 兼容两参构造：默认按显式赋值处理。 */
    public Provenance(String sourceId, String path) {
        this(sourceId, path, Kind.SET);
    }
}
