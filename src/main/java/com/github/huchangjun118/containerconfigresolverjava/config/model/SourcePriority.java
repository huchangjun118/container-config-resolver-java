package com.github.huchangjun118.containerconfigresolverjava.config.model;

/**
 * 配置来源优先级。数值越大优先级越高，合并时高优先级覆盖低优先级。
 */
public enum SourcePriority {
    /** 基础模板：最低优先级，提供镜像自带默认值。 */
    BASE_TEMPLATE(10),
    /** 环境覆盖：环境级别的差异化配置。 */
    ENVIRONMENT(20),
    /** 服务级覆盖：单个服务的个性化配置。 */
    SERVICE(30),
    /** 运行时注入：最高优先级，运行时刻注入的临时配置。 */
    RUNTIME_INJECTION(40);

    private final int weight;

    SourcePriority(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
