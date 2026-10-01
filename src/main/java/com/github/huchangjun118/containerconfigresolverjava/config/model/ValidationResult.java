package com.github.huchangjun118.containerconfigresolverjava.config.model;

import java.util.List;

/**
 * 兼容性校验结论。
 *
 * @param valid  是否通过校验
 * @param errors 不可满足或冲突的原因列表（可解释）
 */
public record ValidationResult(boolean valid, List<String> errors) {

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult fail(List<String> errors) {
        return new ValidationResult(false, List.copyOf(errors));
    }
}
