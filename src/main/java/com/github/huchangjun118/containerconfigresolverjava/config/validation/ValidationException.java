package com.github.huchangjun118.containerconfigresolverjava.config.validation;

/**
 * 校验失败异常：携带可解释的冲突原因列表。
 */
public class ValidationException extends RuntimeException {

    private final transient java.util.List<String> errors;

    public ValidationException(java.util.List<String> errors) {
        super("compatibility validation failed: " + String.join("; ", errors));
        this.errors = java.util.List.copyOf(errors);
    }

    public java.util.List<String> errors() {
        return errors;
    }
}
