package com.github.huchangjun118.containerconfigresolverjava.interpolation;

import com.github.huchangjun118.containerconfigresolverjava.model.ConfigException;

/**
 * 插值表达式语法非法，例如未闭合的 "${"、空引用 "${}"、非法字符。
 */
public class MalformedReferenceException extends ConfigException {

    private final String rawExpression;

    public MalformedReferenceException(String message, String path, String rawExpression) {
        super(message, path);
        this.rawExpression = rawExpression;
    }

    public String rawExpression() {
        return rawExpression;
    }
}
