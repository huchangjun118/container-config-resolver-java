package com.github.huchangjun118.containerconfigresolverjava.config.interpolate;

/**
 * 未定义引用：引用的字段或外部取值不存在。
 */
public class UndefinedReferenceException extends InterpolationException {
    public UndefinedReferenceException(String message) {
        super(message);
    }
}
