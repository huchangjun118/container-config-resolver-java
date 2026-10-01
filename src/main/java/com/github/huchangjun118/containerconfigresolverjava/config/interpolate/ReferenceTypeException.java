package com.github.huchangjun118.containerconfigresolverjava.config.interpolate;

/**
 * 引用类型错误：标量位置引用了集合字段，或引用了不支持插值的字段。
 */
public class ReferenceTypeException extends InterpolationException {
    public ReferenceTypeException(String message) {
        super(message);
    }
}
