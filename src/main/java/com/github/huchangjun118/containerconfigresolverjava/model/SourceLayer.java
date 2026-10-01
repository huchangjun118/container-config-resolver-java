package com.github.huchangjun118.containerconfigresolverjava.model;

/**
 * 配置来源层级。优先级从低到高：
 * IMAGE(0) 镜像自带 -> TEMPLATE(1) 基础模板 -> ENVIRONMENT(2) 环境覆盖
 * -> SERVICE(3) 服务级覆盖 -> RUNTIME(4) 运行时注入。
 */
public enum SourceLayer {
    IMAGE(0, "image"),
    TEMPLATE(1, "template"),
    ENVIRONMENT(2, "environment"),
    SERVICE(3, "service"),
    RUNTIME(4, "runtime");

    private final int priority;
    private final String code;

    SourceLayer(int priority, String code) {
        this.priority = priority;
        this.code = code;
    }

    public int priority() {
        return priority;
    }

    public String code() {
        return code;
    }

    public boolean higherThan(SourceLayer other) {
        return this.priority > other.priority;
    }
}
