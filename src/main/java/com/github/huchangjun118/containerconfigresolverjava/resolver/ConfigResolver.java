package com.github.huchangjun118.containerconfigresolverjava.resolver;

import com.github.huchangjun118.containerconfigresolverjava.interpolation.VariableInterpolator;
import com.github.huchangjun118.containerconfigresolverjava.merge.ConfigMerger;
import com.github.huchangjun118.containerconfigresolverjava.model.CommittedSnapshot;
import com.github.huchangjun118.containerconfigresolverjava.model.ConfigException;
import com.github.huchangjun118.containerconfigresolverjava.model.ConfigSourceProvider;
import com.github.huchangjun118.containerconfigresolverjava.model.ConfigSourceVersion;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolveFailure;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolvedContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.validation.ConfigValidator;
import com.github.huchangjun118.containerconfigresolverjava.validation.ValidationResult;

import java.util.Map;
import java.util.Optional;

/**
 * 配置解析器：合并 -> 插值 -> 校验 -> 原子提交 的编排者。
 *
 * <p>并发语义：内部用单个 volatile 引用持有已提交快照。重载在局部变量上完成
 * 全部计算，成功后才原子替换；任何阶段失败都只更新 lastFailure，已生效快照
 * 保持不变（失败隔离与回退）。读取返回不可变快照引用，永远是某个自洽的完整
 * 版本，不会字段级混合；读取路径不加锁，慢读取不阻塞重载。重载本身
 * synchronized，保证并发重载串行化，避免两个版本乱序生效。</p>
 *
 * <p>连续失败不会让服务不可用：旧快照始终可读，失败原因始终可查询。</p>
 */
public final class ConfigResolver {

    private final ConfigMerger merger;
    private final VariableInterpolator interpolator;
    private final ConfigValidator validator;

    private volatile CommittedSnapshot snapshot;
    private volatile ResolveFailure lastFailure;

    public ConfigResolver(ConfigMerger merger, VariableInterpolator interpolator, ConfigValidator validator) {
        this.merger = merger;
        this.interpolator = interpolator;
        this.validator = validator;
    }

    /**
     * 提交一个已经采集好的来源版本并求值。
     *
     * @return 成功时返回新快照；失败时不改变已生效快照，原因经 {@link #lastFailure()} 查询
     */
    public synchronized Optional<CommittedSnapshot> reload(ConfigSourceVersion version,
                                                            Map<String, String> externals) {
        String revision = version == null ? null : version.revision();
        try {
            if (version == null) {
                throw new ConfigException("来源版本为 null", null);
            }
            // 阶段 1：在局部变量上合并，不触碰已发布快照
            ResolvedContainerConfig merged = merger.merge(version);

            // 阶段 2：插值（未定义/循环/语法错误在此抛出）
            ResolvedContainerConfig resolved = interpolator.interpolate(merged, externals);

            // 阶段 3：兼容性校验
            ValidationResult result = validator.validate(resolved);
            if (!result.valid()) {
                StringBuilder sb = new StringBuilder("校验未通过，存在 ")
                        .append(result.errors().size()).append(" 个错误：");
                result.errors().forEach(e -> sb.append("\n - [").append(e.code())
                        .append("] ").append(e.path()).append(": ").append(e.message()));
                throw new ConfigException(sb.toString(), null);
            }

            // 阶段 4：构造不可变快照并原子发布
            String versionId = VersionHasher.hash(resolved);
            CommittedSnapshot next = new CommittedSnapshot(
                    revision, versionId, resolved, merged, result);
            this.snapshot = next;
            this.lastFailure = null;
            return Optional.of(next);
        } catch (ConfigException e) {
            this.lastFailure = ResolveFailure.of(revision, phaseOf(e), e);
            return Optional.empty();
        } catch (RuntimeException e) {
            this.lastFailure = ResolveFailure.of(revision, "MERGE", e);
            return Optional.empty();
        }
    }

    /**
     * 通过 provider 现场采集一个完整版本再提交；采集异常同样被隔离，
     * 不会污染旧快照，也不会抛给调用方之外导致服务不可用。
     */
    public synchronized Optional<CommittedSnapshot> reloadFrom(ConfigSourceProvider provider,
                                                                Map<String, String> externals) {
        ConfigSourceVersion version;
        try {
            version = provider.load();
        } catch (Exception e) {
            this.lastFailure = ResolveFailure.of(null, "SOURCE",
                    new ConfigException("读取来源失败: " + e.getMessage(), null, e));
            return Optional.empty();
        }
        return reload(version, externals);
    }

    /** 当前已提交快照；尚未成功提交过任何版本时为空。读取无锁。 */
    public Optional<CommittedSnapshot> current() {
        return Optional.ofNullable(snapshot);
    }

    /** 最近一次失败原因（成功提交后会被清空）。 */
    public Optional<ResolveFailure> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    private static String phaseOf(ConfigException e) {
        String pkg = e.getClass().getPackageName();
        if (pkg.endsWith("interpolation")) {
            return "INTERPOLATE";
        }
        if (pkg.endsWith("validation")) {
            return "VALIDATE";
        }
        // 校验阶段在本类内组装的 ConfigException
        if (e.getMessage() != null && e.getMessage().startsWith("校验未通过")) {
            return "VALIDATE";
        }
        return "MERGE";
    }
}
