package com.github.huchangjun118.containerconfigresolverjava.validation;

import java.util.List;

/**
 * 校验结论：valid 仅在不存在 ERROR 时为 true。WARN 不阻止生效，但会被保留可查询。
 */
public record ValidationResult(boolean valid, List<ValidationIssue> issues) {

    public ValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static ValidationResult success(List<ValidationIssue> warnings) {
        return new ValidationResult(true, warnings == null ? List.of() : warnings);
    }

    public static ValidationResult of(List<ValidationIssue> issues) {
        boolean hasError = issues != null && issues.stream().anyMatch(ValidationIssue::isError);
        return new ValidationResult(!hasError, issues);
    }

    public List<ValidationIssue> errors() {
        return issues.stream().filter(ValidationIssue::isError).toList();
    }

    public List<ValidationIssue> warnings() {
        return issues.stream().filter(i -> !i.isError()).toList();
    }
}
