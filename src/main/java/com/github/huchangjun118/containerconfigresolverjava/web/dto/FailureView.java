package com.github.huchangjun118.containerconfigresolverjava.web.dto;

import com.github.huchangjun118.containerconfigresolverjava.model.ResolveFailure;

/** 最近一次失败的可序列化视图。 */
public record FailureView(String revision, String phase, String message, String path, String time) {

    public static FailureView of(ResolveFailure f) {
        return new FailureView(f.revision(), f.phase(), f.message(), f.path(),
                f.time() == null ? null : f.time().toString());
    }
}
