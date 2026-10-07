package com.github.huchangjun118.containerconfigresolverjava.config.interpolate;

/**
 * 插值语法错误：$ 的写法不完整或不合法（如字符串末尾孤零零的 $、
 * $ 后既不是 $ 也不是 {、${ 未闭合、${} 空引用）。
 * 与引用不存在、循环引用、类型错误可区分。
 */
public class InvalidSyntaxException extends InterpolationException {
    public InvalidSyntaxException(String message) {
        super(message);
    }
}
