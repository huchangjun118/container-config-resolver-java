package com.github.huchangjun118.containerconfigresolverjava.config.interpolate;

/**
 * 循环引用：字段引用链形成环。
 */
public class CircularReferenceException extends InterpolationException {
    public CircularReferenceException(String message) {
        super(message);
    }
}
