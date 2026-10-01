package com.github.huchangjun118.containerconfigresolverjava.validation;

import com.github.huchangjun118.containerconfigresolverjava.model.Mount;
import com.github.huchangjun118.containerconfigresolverjava.model.PortBinding;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolvedContainerConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 兼容性校验器：在真正启动前给出可解释结论。任何 ERROR 都会让版本被拒绝，
 * 不允许出现"通过校验却在运行时失败"的定义。
 *
 * <ul>
 *   <li>平台/架构：镜像架构必须在主机原生支持集合内（不假设模拟层）；
 *       显式 platform 需与镜像 os/arch 一致；</li>
 *   <li>端口：配置内部不得重复绑定同一主机端点；不得绑定已占用端口；</li>
 *   <li>挂载：同一容器路径不得重复挂载；/proc、/sys 默认只允许只读，
 *       可写挂载要求非特权也被拒绝（由主机策略决定）；只读根文件系统下
 *       必须存在可写 /tmp 等可写路径时给出 WARN；</li>
 *   <li>安全策略：主机禁止特权时 privileged=true 直接拒绝；</li>
 *   <li>入口点/命令均为空时给 WARN（容器将无进程可启动，由策略决定是否拒绝）。</li>
 * </ul>
 */
public final class ConfigValidator {

    private final HostConstraints constraints;

    public ConfigValidator(HostConstraints constraints) {
        this.constraints = Objects.requireNonNull(constraints, "constraints");
    }

    public ValidationResult validate(ResolvedContainerConfig c) {
        List<ValidationIssue> issues = new ArrayList<>();
        checkPlatform(c, issues);
        checkPorts(c, issues);
        checkMounts(c, issues);
        checkSecurityPolicy(c, issues);
        checkEntrypoint(c, issues);
        return ValidationResult.of(issues);
    }

    private void checkPlatform(ResolvedContainerConfig c, List<ValidationIssue> issues) {
        String hostOs = constraints.os();
        if (c.getImageOs() != null && hostOs != null
                && !c.getImageOs().equalsIgnoreCase(hostOs)) {
            issues.add(ValidationIssue.error("PLATFORM_UNSUPPORTED", "image.os",
                    "镜像操作系统 " + c.getImageOs() + " 与主机 " + hostOs + " 不兼容"));
        }
        String arch = c.getImageArch();
        if (arch != null && !constraints.architectures().isEmpty()
                && constraints.architectures().stream().noneMatch(a -> a.equalsIgnoreCase(arch))) {
            issues.add(ValidationIssue.error("ARCH_UNSUPPORTED", "image.architecture",
                    "镜像架构 " + arch + " 不被主机支持（主机支持: "
                            + String.join(",", constraints.architectures()) + "）"));
        }
        // 显式 platform 形如 os/arch 或 os/arch/variant
        String platform = c.getPlatform();
        if (platform != null && !platform.isBlank()) {
            String[] parts = platform.split("/");
            if (parts.length >= 1 && c.getImageOs() != null
                    && !parts[0].equalsIgnoreCase(c.getImageOs())) {
                issues.add(ValidationIssue.error("PLATFORM_MISMATCH", "platform",
                        "请求 platform " + platform + " 与镜像 os " + c.getImageOs() + " 不一致"));
            }
            if (parts.length >= 2 && c.getImageArch() != null
                    && !parts[1].equalsIgnoreCase(c.getImageArch())) {
                issues.add(ValidationIssue.error("PLATFORM_MISMATCH", "platform",
                        "请求 platform " + platform + " 与镜像架构 " + c.getImageArch() + " 不一致"));
            }
            if (!constraints.architectures().isEmpty()
                    && constraints.architectures().stream()
                        .noneMatch(a -> a.equalsIgnoreCase(parts[parts.length >= 2 ? 1 : 0]))) {
                // 只有当 platform 显式带了架构段时才做主机支持校验
                if (parts.length >= 2) {
                    issues.add(ValidationIssue.error("PLATFORM_UNSUPPORTED", "platform",
                            "请求 platform " + platform + " 的架构不被主机支持"));
                }
            }
        }
    }

    private void checkPorts(ResolvedContainerConfig c, List<ValidationIssue> issues) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < c.getPorts().size(); i++) {
            PortBinding p = c.getPorts().get(i);
            String key = endpointKey(p);
            String path = "ports[" + i + "]";
            if (!seen.add(key)) {
                issues.add(ValidationIssue.error("PORT_CONFLICT", path,
                        "主机端点 " + key + " 在配置中被重复绑定"));
            }
            if (constraints.occupiedHostPorts().contains(key)) {
                issues.add(ValidationIssue.error("PORT_IN_USE", path,
                        "主机端口 " + key + " 已被其他进程占用"));
            }
        }
    }

    private void checkMounts(ResolvedContainerConfig c, List<ValidationIssue> issues) {
        Set<String> targets = new HashSet<>();
        for (int i = 0; i < c.getMounts().size(); i++) {
            Mount m = c.getMounts().get(i);
            String path = "mounts[" + i + "]";
            if (!targets.add(m.target())) {
                issues.add(ValidationIssue.error("MOUNT_TARGET_CONFLICT", path,
                        "容器内挂载点 " + m.target() + " 被重复挂载"));
            }
            if (isProcOrSys(m.target()) && !m.readOnly()) {
                if (!constraints.allowWritableProcSys()) {
                    issues.add(ValidationIssue.error("MOUNT_PROTECTED", path,
                            "不允许以可写方式挂载 " + m.target()
                                    + "（/proc、/sys 必须只读，可通过主机策略放开）"));
                }
            }
        }
        // 只读根文件系统：若所有挂载点都只读，容器内将没有任何可写路径
        if (c.isReadOnlyRootfs() && !c.getMounts().isEmpty()
                && c.getMounts().stream().noneMatch(m -> !m.readOnly() && isWritableCandidate(m.target()))) {
            issues.add(ValidationIssue.warn("NO_WRITABLE_PATH", "readOnlyRootfs",
                    "根文件系统只读且未提供可写挂载点（如 /tmp），依赖临时写文件的应用将运行失败"));
        }
    }

    private void checkSecurityPolicy(ResolvedContainerConfig c, List<ValidationIssue> issues) {
        if (c.isPrivileged() && constraints.privilegedForbidden()) {
            issues.add(ValidationIssue.error("PRIVILEGED_FORBIDDEN", "privileged",
                    "主机平台策略禁止运行特权容器"));
        }
        if (c.isPrivileged() && c.isReadOnlyRootfs()) {
            // 语义上并非不可运行，但通常是配置意图冲突，提醒而非拒绝
            issues.add(ValidationIssue.warn("PRIVILEGED_WITH_READONLY_ROOTFS", "privileged",
                    "同时设置 privileged=true 与 readOnlyRootfs=true，两者意图可能冲突，请确认"));
        }
    }

    private void checkEntrypoint(ResolvedContainerConfig c, List<ValidationIssue> issues) {
        if (c.getEntrypoint().isEmpty() && c.getCommand().isEmpty()) {
            issues.add(ValidationIssue.warn("NO_ENTRYPOINT", "entrypoint",
                    "入口点与命令均为空，容器将没有可执行进程"));
        }
    }

    private static boolean isProcOrSys(String target) {
        return Objects.equals(target, "/proc") || Objects.equals(target, "/sys")
                || target.startsWith("/proc/") || target.startsWith("/sys/");
    }

    /** /tmp、/run 等通常用于承载可写层的路径。 */
    private static boolean isWritableCandidate(String target) {
        return target.equals("/tmp") || target.startsWith("/tmp/")
                || target.equals("/run") || target.startsWith("/run/")
                || target.equals("/var/tmp") || target.startsWith("/var/tmp/");
    }

    private static String endpointKey(PortBinding p) {
        return p.hostIp() + ":" + p.hostPort() + "/" + p.protocol();
    }
}
