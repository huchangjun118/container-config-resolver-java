package com.github.huchangjun118.containerconfigresolverjava.config.interpolate;

/**
 * 插值异常基类。所有插值阶段的失败都必须抛出可区分的异常，不得静默替换。
 */
public class InterpolationException extends RuntimeException {
    public InterpolationException(String message) {
        super(message);
    }

    public InterpolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
