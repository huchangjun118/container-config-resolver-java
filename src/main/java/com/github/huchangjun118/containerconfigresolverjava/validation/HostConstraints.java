package com.github.huchangjun118.containerconfigresolverjava.validation;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 宿主机/平台约束，用于兼容性校验。只描述求值进程所在的本地环境事实，
 * 不访问任何容器运行时或外部服务。
 *
 * @param os                  主机操作系统
 * @param architectures       主机原生支持的架构（含模拟层时可多个，例如 amd64+arm64）
 * @param occupiedHostPorts   已被其他进程占用的主机端点 "hostIp:port/proto"
 * @param privilegedForbidden 是否禁止特权容器（平台策略）
 * @param allowWritableProcSys 是否允许对 /proc、/sys 的可写挂载（默认禁止）
 */
public record HostConstraints(
        String os,
        Set<String> architectures,
        Set<String> occupiedHostPorts,
        boolean privilegedForbidden,
        boolean allowWritableProcSys
) {

    public HostConstraints {
        architectures = architectures == null ? Set.of() : new LinkedHashSet<>(architectures);
        occupiedHostPorts = occupiedHostPorts == null ? Set.of() : new LinkedHashSet<>(occupiedHostPorts);
    }

    /** 用占用端口（默认 tcp，0.0.0.0）快速构建约束。 */
    public static HostConstraints linuxAmd64(Set<Integer> occupiedTcpPorts) {
        Set<String> ep = new LinkedHashSet<>();
        if (occupiedTcpPorts != null) {
            occupiedTcpPorts.forEach(p -> ep.add("0.0.0.0:" + p + "/tcp"));
        }
        return new HostConstraints("linux", Set.of("amd64"), ep, false, false);
    }
}
