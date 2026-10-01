package com.github.huchangjun118.containerconfigresolverjava.model;

import java.util.Objects;

/**
 * 表示一个字段在某个来源中是否被显式提交。
 * absent() 表示该来源未包含此键（合并时跳过）；
 * present(v) 表示该来源显式提交了值，null、空列表同样是显式提交
 * （例如 entrypoint=[] 表示显式清空入口点）。
 */
public final class Presence<T> {

    private static final Presence<?> ABSENT = new Presence<>(false, null);

    private final boolean present;
    private final T value;

    private Presence(boolean present, T value) {
        this.present = present;
        this.value = value;
    }

    public static <T> Presence<T> absent() {
        @SuppressWarnings("unchecked")
        Presence<T> p = (Presence<T>) ABSENT;
        return p;
    }

    public static <T> Presence<T> present(T value) {
        return new Presence<>(true, value);
    }

    public boolean isPresent() {
        return present;
    }

    public boolean isAbsent() {
        return !present;
    }

    public T value() {
        if (!present) {
            throw new IllegalStateException("field is absent");
        }
        return value;
    }

    /** 字段存在时取值，不存在时返回 fallback。 */
    public T orElse(T fallback) {
        return present ? value : fallback;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Presence<?> other)) return false;
        return present == other.present && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(present, value);
    }

    @Override
    public String toString() {
        return present ? "Present(" + value + ")" : "Absent";
    }
}
