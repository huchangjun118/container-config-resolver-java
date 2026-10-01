package com.github.huchangjun118.containerconfigresolverjava.interpolation;

import com.github.huchangjun118.containerconfigresolverjava.model.ConfigException;

/**
 * 引用了不存在的变量/字段，或引用了不允许被插值的路径。
 * 此类错误不得静默替换为空值或原样输出。
 */
public class UndefinedReferenceException extends ConfigException {

    private final String reference;

    public UndefinedReferenceException(String message, String path, String reference) {
        super(message, path);
        this.reference = reference;
    }

    public String reference() {
        return reference;
    }
}
