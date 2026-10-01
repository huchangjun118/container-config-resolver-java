package com.github.huchangjun118.containerconfigresolverjava.interpolation;

import com.github.huchangjun118.containerconfigresolverjava.model.ConfigException;

import java.util.List;

/**
 * 变量插值存在循环依赖。携带完整的引用链以便解释。
 */
public class CircularReferenceException extends ConfigException {

    private final List<String> chain;

    public CircularReferenceException(String message, String path, List<String> chain) {
        super(message + " (cycle: " + String.join(" -> ", chain) + ")", path);
        this.chain = List.copyOf(chain);
    }

    public List<String> chain() {
        return chain;
    }
}
