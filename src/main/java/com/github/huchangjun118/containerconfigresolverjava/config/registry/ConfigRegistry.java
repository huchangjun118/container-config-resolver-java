package com.github.huchangjun118.containerconfigresolverjava.config.registry;

import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.Interpolator;
import com.github.huchangjun118.containerconfigresolverjava.config.merge.ConfigMerger;
import com.github.huchangjun118.containerconfigresolverjava.config.merge.ConfigMerger.MergeOutcome;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ConfigSource;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ResolvedConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ValidationResult;
import com.github.huchangjun118.containerconfigresolverjava.config.validation.CompatibilityValidator;
import com.github.huchangjun118.containerconfigresolverjava.config.validation.ValidationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置注册中心：持有当前已提交的完整版本，支持并发重载与失败隔离。
 *
 * <p>并发模型：
 * <ul>
 *   <li>读取永远通过 {@link AtomicReference} 返回某个已提交的不可变快照，
 *       任一时刻读到的都是自洽的单个版本，不会出现字段级混合；</li>
 *   <li>重载采用写时复制：在独立栈上完成 合并 -> 插值 -> 校验，通过后才原子替换引用，
 *       慢读取不会阻塞重载，重载也不影响读取；</li>
 *   <li>某次重载失败时，上一版本继续生效，失败原因与连续失败次数可查询，服务不中断。</li>
 * </ul>
 */
public class ConfigRegistry {

    private final SourceProvider sourceProvider;
    private final ExternalValueProvider externalValueProvider;
    private final ConfigMerger merger;
    private final Interpolator interpolator;
    private final CompatibilityValidator validator;

    private final AtomicReference<ResolvedConfig> current = new AtomicReference<>();
    private final AtomicReference<ReloadFailure> lastFailure = new AtomicReference<>();
    private final AtomicLong versionGenerator = new AtomicLong(0);
    private final AtomicLong consecutiveFailures = new AtomicLong(0);

    public ConfigRegistry(SourceProvider sourceProvider,
                          ExternalValueProvider externalValueProvider,
                          ConfigMerger merger,
                          Interpolator interpolator,
                          CompatibilityValidator validator) {
        this.sourceProvider = sourceProvider;
        this.externalValueProvider = externalValueProvider;
        this.merger = merger;
        this.interpolator = interpolator;
        this.validator = validator;
    }

    /** 重载结果。 */
    public sealed interface ReloadResult permits ReloadResult.Success, ReloadResult.Failure {
        record Success(long version, ResolvedConfig config) implements ReloadResult {}
        record Failure(long attemptedVersion, String reason, Instant at) implements ReloadResult {}
    }

    /** 失败记录。 */
    public record ReloadFailure(long attemptedVersion, String reason, Instant at, long consecutiveFailures) {
    }

    /** 读取当前已提交版本；从未成功提交时返回 null。 */
    public ResolvedConfig current() {
        return current.get();
    }

    /**
     * 触发一次重载：拉取来源 -> 合并 -> 插值 -> 校验 -> 提交。
     * 任一步失败都保留上一版本并记录失败原因，绝不污染已生效结果。
     */
    public ReloadResult reload() {
        long attempted = versionGenerator.incrementAndGet();
        try {
            List<ConfigSource> sources = sourceProvider.load();
            MergeOutcome merged = merger.merge(sources);

            Map<String, String> external = externalValueProvider == null ? Map.of() : externalValueProvider.get();
            ContainerConfig interpolated = interpolator.interpolate(merged.config(), external);

            ValidationResult validation = validator.validate(interpolated);
            if (!validation.valid()) {
                throw new ValidationException(validation.errors());
            }

            ResolvedConfig committed = new ResolvedConfig(
                    attempted, interpolated, merged.provenance(), merged.sources(), Instant.now());
            current.set(committed);
            consecutiveFailures.set(0);
            lastFailure.set(null);
            return new ReloadResult.Success(attempted, committed);
        } catch (RuntimeException e) {
            long fails = consecutiveFailures.incrementAndGet();
            String reason = describe(e);
            ReloadFailure failure = new ReloadFailure(attempted, reason, Instant.now(), fails);
            lastFailure.set(failure);
            return new ReloadResult.Failure(attempted, reason, failure.at());
        }
    }

    private String describe(Throwable e) {
        if (e instanceof ValidationException ve) {
            return ve.getMessage();
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    public ReloadFailure lastFailure() {
        return lastFailure.get();
    }

    public long consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public long currentVersion() {
        ResolvedConfig c = current.get();
        return c == null ? 0 : c.version();
    }
}
