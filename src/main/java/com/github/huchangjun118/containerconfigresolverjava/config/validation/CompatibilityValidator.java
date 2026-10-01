package com.github.huchangjun118.containerconfigresolverjava.config.validation;

import com.github.huchangjun118.containerconfigresolverjava.config.model.ContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.model.PortMapping;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ValidationResult;
import com.github.huchangjun118.containerconfigresolverjava.config.model.VolumeMount;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 兼容性校验器：对平台、架构、卷挂载、端口占用、特权与只读要求等组合给出可解释结论。
 *
 * <p>策略（保守策略，宁可拒绝不可满足的组合，也不放过任何会在运行时失败的定义）：
 * <ol>
 *   <li>平台必须受支持（linux/amd64、linux/arm64）；</li>
 *   <li>若同时指定 arch，必须与 platform 的架构分量一致；</li>
 *   <li>端口必须在 1-65535 范围内，且宿主机端口不得重复占用；</li>
 *   <li>卷挂载目标路径必须为绝对路径，且容器内挂载点不得重复；</li>
 *   <li>特权模式（privileged）与只读根文件系统（readOnlyRootFilesystem）互斥；</li>
 *   <li>入口点与命令不得同时为空（否则容器无法启动）。</li>
 * </ol>
 */
public class CompatibilityValidator {

    /** 受支持的平台（os/arch）。 */
    public static final Set<String> SUPPORTED_PLATFORMS = Set.of("linux/amd64", "linux/arm64");

    public ValidationResult validate(ContainerConfig c) {
        List<String> errors = new ArrayList<>();

        validatePlatform(c, errors);
        validatePorts(c, errors);
        validateVolumes(c, errors);
        validatePrivilegedReadOnly(c, errors);
        validateEntrypointCommand(c, errors);

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    private void validatePlatform(ContainerConfig c, List<String> errors) {
        String platform = c.platform();
        if (platform != null) {
            int slash = platform.indexOf('/');
            if (slash <= 0 || slash == platform.length() - 1) {
                errors.add("platform must be in 'os/arch' format, got: " + platform);
            } else if (!SUPPORTED_PLATFORMS.contains(platform)) {
                errors.add("unsupported platform: " + platform + " (supported: " + SUPPORTED_PLATFORMS + ")");
            } else if (c.arch() != null && !c.arch().isBlank()) {
                String platformArch = platform.substring(slash + 1);
                if (!platformArch.equals(c.arch())) {
                    errors.add("arch '" + c.arch() + "' conflicts with platform '" + platform + "'");
                }
            }
        }
    }

    private void validatePorts(ContainerConfig c, List<String> errors) {
        List<PortMapping> ports = c.ports();
        if (ports == null) {
            return;
        }
        Set<Integer> usedHostPorts = new HashSet<>();
        for (PortMapping p : ports) {
            checkPort(p.hostPort(), "hostPort", errors);
            checkPort(p.containerPort(), "containerPort", errors);
            if (!usedHostPorts.add(p.hostPort())) {
                errors.add("host port conflict: " + p.hostPort() + " is bound by multiple mappings");
            }
        }
    }

    private void checkPort(int port, String name, List<String> errors) {
        if (port < 1 || port > 65535) {
            errors.add(name + " out of range (1-65535): " + port);
        }
    }

    private void validateVolumes(ContainerConfig c, List<String> errors) {
        List<VolumeMount> volumes = c.volumes();
        if (volumes == null) {
            return;
        }
        Set<String> usedTargets = new HashSet<>();
        for (VolumeMount v : volumes) {
            if (v.target() == null || v.target().isBlank()) {
                errors.add("volume mount target must not be blank");
                continue;
            }
            if (!v.target().startsWith("/")) {
                errors.add("volume mount target must be an absolute path: " + v.target());
            }
            if (!usedTargets.add(v.target())) {
                errors.add("volume mount conflict: target '" + v.target() + "' is mounted multiple times");
            }
        }
    }

    private void validatePrivilegedReadOnly(ContainerConfig c, List<String> errors) {
        if (Boolean.TRUE.equals(c.privileged()) && Boolean.TRUE.equals(c.readOnlyRootFilesystem())) {
            errors.add("privileged mode is incompatible with readOnlyRootFilesystem=true");
        }
    }

    private void validateEntrypointCommand(ContainerConfig c, List<String> errors) {
        boolean noEntrypoint = c.entrypoint() == null || c.entrypoint().isEmpty();
        boolean noCommand = c.command() == null || c.command().isEmpty();
        if (noEntrypoint && noCommand) {
            errors.add("entrypoint and command must not both be empty");
        }
    }
}
