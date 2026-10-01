package com.github.huchangjun118.containerconfigresolverjava.merge;

import com.github.huchangjun118.containerconfigresolverjava.model.ConfigSourceVersion;
import com.github.huchangjun118.containerconfigresolverjava.model.FieldOrigin;
import com.github.huchangjun118.containerconfigresolverjava.model.ImageMetadata;
import com.github.huchangjun118.containerconfigresolverjava.model.Mount;
import com.github.huchangjun118.containerconfigresolverjava.model.PortBinding;
import com.github.huchangjun118.containerconfigresolverjava.model.Presence;
import com.github.huchangjun118.containerconfigresolverjava.model.RawContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolvedContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.model.SourceLayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层合并器：按 IMAGE -> TEMPLATE -> ENVIRONMENT -> SERVICE -> RUNTIME 的顺序
 * 合并一个完整来源版本，产出带字段追溯的运行定义。
 *
 * <ul>
 *   <li>标量（image/platform/workingDir/user/privileged/readOnlyRootfs）：高优先级整体替换；</li>
 *   <li>env/labels：按 key 字段级合并，高优先级覆盖同名 key；</li>
 *   <li>ports/capabilities：跨层追加、按内容去重；
 *       mounts：跨层追加，相同容器挂载点由高优先级整条覆盖；</li>
 *   <li>entrypoint/command：不可分割的整体替换 + Docker CMD 语义：
 *       上层重新声明 entrypoint 后，镜像 CMD 不再自动继承（视为重置），
 *       除非上层也声明 command；仅覆盖 command 时 entrypoint 照常继承。</li>
 * </ul>
 */
public final class ConfigMerger {

    public ConfigMerger() {
    }

    public ResolvedContainerConfig merge(ConfigSourceVersion version) {
        ImageMetadata img = version.image();

        // ---- 标量：以镜像层为底，逐层整体替换 ----
        String image = img.image();
        String imageOs = img.os();
        String imageArch = img.architecture();
        FieldOrigin imageOrigin = image == null ? null
                : FieldOrigin.of("image", SourceLayer.IMAGE, img.image());
        String platform = null;
        String workingDir = null;
        String user = null;
        FieldOrigin platformOrigin = null;
        FieldOrigin workingDirOrigin = null;
        FieldOrigin userOrigin = null;
        boolean privileged = false;
        boolean readOnlyRootfs = false;
        FieldOrigin privilegedOrigin = null;
        FieldOrigin readOnlyOrigin = null;

        // ---- 字段级合并的 map：逐 key 记录来源 ----
        Map<String, String> env = new LinkedHashMap<>();
        Map<String, FieldOrigin> envOrigin = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, FieldOrigin> labelsOrigin = new LinkedHashMap<>();

        // ---- 追加型列表：记录每个最终元素的来源 ----
        List<PortBinding> ports = new ArrayList<>();
        List<FieldOrigin> portOrigin = new ArrayList<>();
        List<Mount> mounts = new ArrayList<>();
        List<FieldOrigin> mountOrigin = new ArrayList<>();
        List<String> capabilities = new ArrayList<>();
        List<FieldOrigin> capOrigin = new ArrayList<>();

        // ---- entrypoint / command 的 Docker 语义处理 ----
        // 初始状态完全来自镜像；之后"最后一个声明 entrypoint 的层"决定 CMD 是否重置。
        List<String> entrypoint = new ArrayList<>(img.entrypoint());
        List<String> command = new ArrayList<>(img.command());
        FieldOrigin entrypointOrigin = img.entrypoint().isEmpty() ? null
                : listOrigin("entrypoint", SourceLayer.IMAGE, img.image(), img.entrypoint());
        FieldOrigin commandOrigin = img.command().isEmpty() ? null
                : listOrigin("command", SourceLayer.IMAGE, img.image(), img.command());
        boolean cmdResetByEntrypoint = false;
        String cmdResetDetail = null;

        for (ConfigSourceVersion.LayeredSource ls : version.layers()) {
            RawContainerConfig c = ls.config();
            SourceLayer layer = ls.layer();
            String name = ls.name();

            if (c.getImage().isPresent()) {
                image = c.getImage().value();
                imageOrigin = FieldOrigin.of("image", layer, name);
            }
            if (c.getPlatform().isPresent()) {
                platform = c.getPlatform().value();
                platformOrigin = FieldOrigin.of("platform", layer, name);
            }
            if (c.getWorkingDir().isPresent()) {
                workingDir = c.getWorkingDir().value();
                workingDirOrigin = FieldOrigin.of("workingDir", layer, name);
            }
            if (c.getUser().isPresent()) {
                user = c.getUser().value();
                userOrigin = FieldOrigin.of("user", layer, name);
            }
            if (c.getPrivileged().isPresent()) {
                privileged = Boolean.TRUE.equals(c.getPrivileged().value());
                privilegedOrigin = FieldOrigin.of("privileged", layer, name);
            }
            if (c.getReadOnlyRootfs().isPresent()) {
                readOnlyRootfs = Boolean.TRUE.equals(c.getReadOnlyRootfs().value());
                readOnlyOrigin = FieldOrigin.of("readOnlyRootfs", layer, name);
            }

            mergeMap(env, envOrigin, "env", c.getEnv(), layer, name);
            mergeMap(labels, labelsOrigin, "labels", c.getLabels(), layer, name);
            mergePorts(ports, portOrigin, c.getPorts(), layer, name);
            mergeMounts(mounts, mountOrigin, c.getMounts(), layer, name);
            mergeCapabilities(capabilities, capOrigin, c.getCapabilities(), layer, name);

            // entrypoint/command：整体替换。任何一层显式提交 entrypoint（含空列表），
            // 都把当前 entrypoint 整体换成该值，并令镜像/此前的 CMD 失效（重置）；
            // 若同一层也显式提交 command，则以该层 command 为准（重置后重新提供）。
            if (c.getEntrypoint().isPresent()) {
                List<String> ep = c.getEntrypoint().value() == null
                        ? List.of() : List.copyOf(c.getEntrypoint().value());
                entrypoint = new ArrayList<>(ep);
                entrypointOrigin = listOrigin("entrypoint", layer, name, ep);
                command = new ArrayList<>();
                commandOrigin = null;
                cmdResetByEntrypoint = true;
                cmdResetDetail = "镜像/下层 CMD 已在 " + layer.code() + " 层(" + name
                        + ")覆盖 entrypoint 时重置，需由同层或更高层显式提供 command";
            }
            if (c.getCommand().isPresent()) {
                List<String> cmd = c.getCommand().value() == null
                        ? List.of() : List.copyOf(c.getCommand().value());
                command = new ArrayList<>(cmd);
                commandOrigin = listOrigin("command", layer, name, cmd);
                cmdResetByEntrypoint = false;
                cmdResetDetail = null;
            }
        }

        if (cmdResetByEntrypoint && commandOrigin != null) {
            commandOrigin = commandOrigin.withDetail(cmdResetDetail);
        } else if (cmdResetByEntrypoint) {
            // command 被重置为空：在 command 路径上留下可解释的追溯
            commandOrigin = FieldOrigin.of("command",
                            lastEntrypointLayer(version), "<reset>")
                    .withDetail(cmdResetDetail);
        }

        // ---- 组装追溯表 ----
        Map<String, FieldOrigin> origins = new LinkedHashMap<>();
        putIfNotNull(origins, "image", imageOrigin);
        putIfNotNull(origins, "platform", platformOrigin);
        putIfNotNull(origins, "workingDir", workingDirOrigin);
        putIfNotNull(origins, "user", userOrigin);
        putIfNotNull(origins, "privileged", privilegedOrigin);
        putIfNotNull(origins, "readOnlyRootfs", readOnlyOrigin);
        putIfNotNull(origins, "entrypoint", entrypointOrigin);
        if (entrypointOrigin != null) {
            for (int i = 0; i < entrypoint.size(); i++) {
                origins.put("entrypoint[" + i + "]",
                        FieldOrigin.of("entrypoint[" + i + "]",
                                entrypointOrigin.layer(), entrypointOrigin.source()));
            }
        }
        putIfNotNull(origins, "command", commandOrigin);
        if (commandOrigin != null) {
            for (int i = 0; i < command.size(); i++) {
                origins.put("command[" + i + "]",
                        FieldOrigin.of("command[" + i + "]",
                                commandOrigin.layer(), commandOrigin.source()));
            }
        }
        for (Map.Entry<String, FieldOrigin> e : envOrigin.entrySet()) {
            origins.put("env." + e.getKey(), e.getValue());
        }
        for (Map.Entry<String, FieldOrigin> e : labelsOrigin.entrySet()) {
            origins.put("labels." + e.getKey(), e.getValue());
        }
        for (int i = 0; i < ports.size(); i++) {
            FieldOrigin o = portOrigin.get(i);
            origins.put("ports[" + i + "]",
                    new FieldOrigin("ports[" + i + "]", o.layer(), o.source(), o.detail()));
        }
        for (int i = 0; i < mounts.size(); i++) {
            FieldOrigin o = mountOrigin.get(i);
            String idx = "mounts[" + i + "]";
            origins.put(idx, new FieldOrigin(idx, o.layer(), o.source(), o.detail()));
            origins.put(idx + ".target", o);
            origins.put(idx + ".source", o);
        }
        for (int i = 0; i < capabilities.size(); i++) {
            FieldOrigin o = capOrigin.get(i);
            origins.put("capabilities[" + i + "]",
                    new FieldOrigin("capabilities[" + i + "]", o.layer(), o.source(), o.detail()));
        }

        return new ResolvedContainerConfig(
                image, platform, imageOs, imageArch,
                entrypoint, command, workingDir, user,
                env, labels, ports, mounts, capabilities,
                privileged, readOnlyRootfs, origins);
    }

    private static void mergeMap(Map<String, String> target,
                                 Map<String, FieldOrigin> origins,
                                 String path,
                                 Presence<Map<String, String>> value,
                                 SourceLayer layer, String name) {
        if (value.isAbsent()) {
            return;
        }
        Map<String, String> provided = value.value();
        if (provided == null) {
            // 显式提交 null：视为把整个 map 重置为空
            target.clear();
            origins.clear();
            return;
        }
        for (Map.Entry<String, String> e : provided.entrySet()) {
            target.put(e.getKey(), e.getValue());
            origins.put(e.getKey(),
                    FieldOrigin.of(path + "." + e.getKey(), layer, name));
        }
    }

    private static void mergePorts(List<PortBinding> target, List<FieldOrigin> origins,
                                   Presence<List<PortBinding>> value,
                                   SourceLayer layer, String name) {
        if (value.isAbsent() || value.value() == null) {
            if (value.isPresent()) {
                target.clear();
                origins.clear();
            }
            return;
        }
        for (int i = 0; i < value.value().size(); i++) {
            PortBinding p = value.value().get(i);
            if (!target.contains(p)) {
                target.add(p);
                origins.add(FieldOrigin.of("ports", layer, name));
            }
        }
    }

    private static void mergeMounts(List<Mount> target, List<FieldOrigin> origins,
                                    Presence<List<Mount>> value,
                                    SourceLayer layer, String name) {
        if (value.isAbsent() || value.value() == null) {
            if (value.isPresent()) {
                target.clear();
                origins.clear();
            }
            return;
        }
        for (Mount m : value.value()) {
            int existing = indexOfTarget(target, m.target());
            if (existing >= 0) {
                // 相同容器挂载点：高优先级整条覆盖（保留位置，更新内容与来源）
                target.set(existing, m);
                origins.set(existing, FieldOrigin.of("mounts", layer, name));
            } else {
                target.add(m);
                origins.add(FieldOrigin.of("mounts", layer, name));
            }
        }
    }

    private static void mergeCapabilities(List<String> target, List<FieldOrigin> origins,
                                          Presence<List<String>> value,
                                          SourceLayer layer, String name) {
        if (value.isAbsent() || value.value() == null) {
            if (value.isPresent()) {
                target.clear();
                origins.clear();
            }
            return;
        }
        for (String cap : value.value()) {
            String normalized = cap == null ? null : cap.trim();
            if (normalized == null || normalized.isEmpty() || target.contains(normalized)) {
                continue;
            }
            target.add(normalized);
            origins.add(FieldOrigin.of("capabilities", layer, name));
        }
    }

    private static int indexOfTarget(List<Mount> mounts, String targetPath) {
        for (int i = 0; i < mounts.size(); i++) {
            if (mounts.get(i).target().equals(targetPath)) {
                return i;
            }
        }
        return -1;
    }

    private static FieldOrigin listOrigin(String path, SourceLayer layer, String name, List<String> values) {
        return FieldOrigin.of(path, layer, name)
                .withDetail(values.isEmpty() ? "显式提交为空列表（清空）" : null);
    }

    private static SourceLayer lastEntrypointLayer(ConfigSourceVersion version) {
        SourceLayer last = SourceLayer.IMAGE;
        for (ConfigSourceVersion.LayeredSource ls : version.layers()) {
            if (ls.config().getEntrypoint().isPresent()) {
                last = ls.layer();
            }
        }
        return last;
    }

    private static void putIfNotNull(Map<String, FieldOrigin> origins, String path, FieldOrigin o) {
        if (o != null) {
            origins.put(path, o);
        }
    }
}
