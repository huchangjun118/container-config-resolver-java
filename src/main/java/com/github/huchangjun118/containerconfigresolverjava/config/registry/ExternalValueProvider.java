package com.github.huchangjun118.containerconfigresolverjava.config.registry;

import java.util.Map;

/**
 * 外部取值提供者（如环境变量），供插值使用。
 */
@FunctionalInterface
public interface ExternalValueProvider {

    Map<String, String> get();
}
