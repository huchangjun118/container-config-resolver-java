package com.github.huchangjun118.containerconfigresolverjava.config.model;

/**
 * 字段取值来源：sourceId + 字段在该来源内的路径 + 取值方式。
 *
 * @param sourceId 提供该字段的来源 id
 * @param path     字段在该来源内的路径
 * @param kind     该字段在来源中的取值方式（取值 / 显式重置 / 耦合重置）
 */
public record Provenance(String sourceId, String path, Kind kind) {

    /** 字段最终取值的形成方式。 */
    public enum Kind {
        /** 来源显式给出了取值（含非空集合、非空标量）。 */
        VALUE,
        /** 显式重置：来源用空集合显式把该字段重置为空。 */
        EXPLICIT_RESET,
        /** 耦合重置：同层给出非空入口点但未给命令，命令被联动重置为空。 */
        COUPLED_RESET
    }

    /** 未指定方式时按普通取值处理，保持既有来源记录的兼容。 */
    public Provenance(String sourceId, String path) {
        this(sourceId, path, Kind.VALUE);
    }

    /** 该字段最终是否为空重置（无论显式重置还是耦合重置）。 */
    public boolean reset() {
        return kind == Kind.EXPLICIT_RESET || kind == Kind.COUPLED_RESET;
    }
}
