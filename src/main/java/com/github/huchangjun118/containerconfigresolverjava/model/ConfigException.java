package com.github.huchangjun118.containerconfigresolverjava.model;

/**
 * 求值流水线中所有错误的基类。携带发生错误的逻辑字段路径。
 */
public class ConfigException extends RuntimeException {

    private final String path;

    public ConfigException(String message, String path) {
        super(message);
        this.path = path;
    }

    public ConfigException(String message, String path, Throwable cause) {
        super(message, cause);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
