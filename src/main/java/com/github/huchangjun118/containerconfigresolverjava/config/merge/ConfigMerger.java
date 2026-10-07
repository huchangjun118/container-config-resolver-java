package com.github.huchangjun118.containerconfigresolverjava.config.merge;

import com.github.huchangjun118.containerconfigresolverjava.config.model.ConfigSource;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.model.PortMapping;
import com.github.huchangjun118.containerconfigresolverjava.config.model.Provenance;
import com.github.huchangjun118.containerconfigresolverjava.config.model.VolumeMount;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 配置合并器：按确定的优先级顺序（基础模板 -> 环境覆盖 -> 服务级覆盖 -> 运行时注入）合并。
 *
 * <p>合并语义：
 * <ul>
 *   <li>标量字段（image/platform/arch/workingDir/user/privileged/readOnlyRootFilesystem）：
 *       高优先级非空值整体替换低优先级值；</li>
 *   <li>映射字段（env/labels）：字段级合并，低优先级的键保留，高优先级的同键覆盖；</li>
 *   <li>列表字段（ports/volumes）：高优先级非空列表整体替换；显式空列表表示重置；</li>
 *   <li>入口点与命令遵循容器运行时（Docker）耦合语义：<ul>
 *       <li>同层同时给出入口点与命令：两者都生效（空列表为显式重置）；</li>
 *       <li>同层只给出非空入口点、未给出命令：命令被联动重置为空（COUPLED_RESET），
 *           不再沿用镜像或低优先级来源的旧命令；</li>
 *       <li>同层只给出命令：入口点继续继承；</li>
 *       <li>同层显式给出空入口点：只重置入口点本身，不联动命令。</li>
 *   </ul></li>
 * </ul>
 * 重置与“恰好覆盖成空值”通过 {@link Provenance.Kind} 区分。
 */
public class ConfigMerger {

    /** 合并结果。 */
    public record MergeOutcome(ContainerConfig config,
                               Map<String, Provenance> provenance,
                               List<String> sources) {
    }

    public MergeOutcome merge(List<ConfigSource> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("at least one source is required");
        }

        // 按优先级从低到高排序；同优先级按来源 id 排序保证确定性。
        List<ConfigSource> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator
                .comparingInt((ConfigSource s) -> s.priority().weight())
                .thenComparing(ConfigSource::id));

        ContainerConfig.Builder b = ContainerConfig.builder();
        Map<String, Provenance> prov = new LinkedHashMap<>();

        // 映射字段在多次调用间累加（字段级合并）。
        Map<String, String> envAcc = new LinkedHashMap<>();
        Map<String, String> labelsAcc = new LinkedHashMap<>();

        for (ConfigSource source : ordered) {
            ContainerConfig f = source.fragment();
            String sid = source.id();

            mergeScalar(b, "image", f.image(), sid, prov, ContainerConfig.Builder::image);
            mergeScalar(b, "platform", f.platform(), sid, prov, ContainerConfig.Builder::platform);
            mergeScalar(b, "arch", f.arch(), sid, prov, ContainerConfig.Builder::arch);
            mergeScalar(b, "workingDir", f.workingDir(), sid, prov, ContainerConfig.Builder::workingDir);
            mergeScalar(b, "user", f.user(), sid, prov, ContainerConfig.Builder::user);
            mergeScalar(b, "privileged", f.privileged(), sid, prov, ContainerConfig.Builder::privileged);
            mergeScalar(b, "readOnlyRootFilesystem", f.readOnlyRootFilesystem(), sid, prov,
                    ContainerConfig.Builder::readOnlyRootFilesystem);

            mergeMap(envAcc, f.env(), "env", sid, prov);
            mergeMap(labelsAcc, f.labels(), "labels", sid, prov);

            mergeEntrypointCommand(b, f, sid, prov);
            mergePorts(b, f.ports(), sid, prov);
            mergeVolumes(b, f.volumes(), sid, prov);
        }

        b.env(envAcc);
        b.labels(labelsAcc);

        List<String> sourceIds = ordered.stream().map(ConfigSource::id).toList();
        return new MergeOutcome(b.build(), prov, sourceIds);
    }

    private <T> void mergeScalar(ContainerConfig.Builder b, String field, T value, String sourceId,
                                 Map<String, Provenance> prov,
                                 BiConsumer<ContainerConfig.Builder, T> setter) {
        if (value != null) {
            setter.accept(b, value);
            prov.put(field, new Provenance(sourceId, field));
        }
    }

    private void mergeMap(Map<String, String> acc, Map<String, String> value, String field,
                          String sourceId, Map<String, Provenance> prov) {
        if (value != null) {
            acc.putAll(value);
            for (String key : value.keySet()) {
                prov.put(field + "." + key, new Provenance(sourceId, field + "." + key));
            }
        }
    }

    /**
     * 入口点与命令的耦合合并。
     *
     * <p>对单个来源：
     * <ul>
     *   <li>入口点非 null：整体替换（空列表为显式重置 EXPLICIT_RESET）；</li>
     *   <li>命令非 null：整体替换（空列表为显式重置 EXPLICIT_RESET）；</li>
     *   <li>入口点非 null 且非空、命令为 null：命令被联动重置为空（COUPLED_RESET），
     *       追溯到触发该联动的来源与入口点路径；</li>
     *   <li>其余情况未指定的字段保持继承。</li>
     * </ul>
     */
    private void mergeEntrypointCommand(ContainerConfig.Builder b, ContainerConfig f, String sourceId,
                                        Map<String, Provenance> prov) {
        List<String> entrypoint = f.entrypoint();
        List<String> command = f.command();

        if (entrypoint != null) {
            b.entrypoint(new ArrayList<>(entrypoint));
            prov.put("entrypoint", new Provenance(sourceId, "entrypoint",
                    entrypoint.isEmpty() ? Provenance.Kind.EXPLICIT_RESET : Provenance.Kind.VALUE));
        }

        if (command != null) {
            b.command(new ArrayList<>(command));
            prov.put("command", new Provenance(sourceId, "command",
                    command.isEmpty() ? Provenance.Kind.EXPLICIT_RESET : Provenance.Kind.VALUE));
        } else if (entrypoint != null && !entrypoint.isEmpty()) {
            // 只给非空入口点、没给命令：镜像/低优先级的旧命令不得拼接在新入口点后。
            b.command(new ArrayList<>());
            prov.put("command", new Provenance(sourceId, "entrypoint", Provenance.Kind.COUPLED_RESET));
        }
    }

    private void mergePorts(ContainerConfig.Builder b, List<PortMapping> value, String sourceId,
                            Map<String, Provenance> prov) {
        if (value != null) {
            b.ports(new ArrayList<>(value));
            prov.put("ports", new Provenance(sourceId, "ports"));
        }
    }

    private void mergeVolumes(ContainerConfig.Builder b, List<VolumeMount> value, String sourceId,
                              Map<String, Provenance> prov) {
        if (value != null) {
            b.volumes(new ArrayList<>(value));
            prov.put("volumes", new Provenance(sourceId, "volumes"));
        }
    }
}
