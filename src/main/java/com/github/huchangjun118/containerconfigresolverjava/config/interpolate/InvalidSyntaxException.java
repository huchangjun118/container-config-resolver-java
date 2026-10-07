package com.github.huchangjun118.containerconfigresolverjava.config.interpolate;

/**
 * 非法插值写法：{@code $} 既不构成合法引用也不构成合法转义。
 *
 * <p>典型场景：孤零零的 {@code $}（含落在字符串末尾）、{@code $x} 之类的跟随字符、
 * 未闭合的 {@code ${...}、空引用 {@code ${}}、空外部引用 {@code ${env:}}。
 * 该错误属于写法本身不合法，与引用目标是否存在无关，绝不原样输出，也不静默替换为空串。
 */
public class InvalidSyntaxException extends InterpolationException {

    private final String offendingText;

    public InvalidSyntaxException(String message, String offendingText) {
        super(message);
        this.offendingText = offendingText;
    }

    /** 触发错误的原始文本（字段模板）。 */
    public String offendingText() {
        return offendingText;
    }
}
