package com.github.huchangjun118.containerconfigresolverjava.config.model;

/**
 * 端口映射：hostPort 为宿主机端口，containerPort 为容器内端口。
 */
public record PortMapping(int hostPort, int containerPort, String protocol) {
}
