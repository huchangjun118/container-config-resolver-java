package com.github.huchangjun118.containerconfigresolverjava.validation;

/**
 * 单条校验结论。
 *
 * @param severity ERROR 不可满足（必须拒绝）/ WARN 可解释的提醒
 * @param code     稳定的问题码，便于程序化处理，例如 PORT_IN_USE / PLATFORM_UNSUPPORTED
 * @param path     相关字段路径，可空
 * @param message  人类可读的原因说明
 */
public record ValidationIssue(Severity severity, String code, String path, String message) {

    public enum Severity { ERROR, WARN }

    public static ValidationIssue error(String code, String path, String message) {
        return new ValidationIssue(Severity.ERROR, code, path, message);
    }

    public static ValidationIssue warn(String code, String path, String message) {
        return new ValidationIssue(Severity.WARN, code, path, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
