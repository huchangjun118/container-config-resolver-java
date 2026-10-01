package com.github.huchangjun118.containerconfigresolverjava.model;

import java.util.Objects;

/**
 * 端口绑定：主机端口 -> 容器端口/协议。
 *
 * @param hostIp           绑定的主机地址，null 或空表示 0.0.0.0
 * @param hostPort         主机端口（必须为正整数）
 * @param containerPort    容器内监听端口（必须为正整数）
 * @param protocol         协议 tcp/udp
 */
public record PortBinding(String hostIp, int hostPort, int containerPort, String protocol) {

    public PortBinding {
        if (hostPort <= 0 || hostPort > 65535) {
            throw new IllegalArgumentException("invalid host port: " + hostPort);
        }
        if (containerPort <= 0 || containerPort > 65535) {
            throw new IllegalArgumentException("invalid container port: " + containerPort);
        }
        protocol = protocol == null || protocol.isBlank() ? "tcp" : protocol.toLowerCase();
        hostIp = hostIp == null ? "0.0.0.0" : hostIp;
    }

    /** 同一主机端点（ip+端口+协议）的两条绑定互相冲突。 */
    public boolean sameHostEndpoint(PortBinding other) {
        return hostPort == other.hostPort
                && protocol.equals(other.protocol)
                && Objects.equals(hostIp, other.hostIp);
    }
}
