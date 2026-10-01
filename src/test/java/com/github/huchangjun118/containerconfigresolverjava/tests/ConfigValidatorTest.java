package com.github.huchangjun118.containerconfigresolverjava.tests;

import com.github.huchangjun118.containerconfigresolverjava.model.Mount;
import com.github.huchangjun118.containerconfigresolverjava.model.PortBinding;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolvedContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.validation.ConfigValidator;
import com.github.huchangjun118.containerconfigresolverjava.validation.HostConstraints;
import com.github.huchangjun118.containerconfigresolverjava.validation.ValidationIssue;
import com.github.huchangjun118.containerconfigresolverjava.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigValidatorTest {

    private final HostConstraints defaultHost =
            new HostConstraints("linux", Set.of("amd64"), Set.of(), false, false);

    private ResolvedContainerConfig base() {
        return ResolvedContainerConfig.builder()
                .image("nginx:1.27")
                .imageOs("linux").imageArch("amd64")
                .entrypoint(List.of("/ep")).command(List.of("run"))
                .build();
    }

    private ValidationResult validate(ResolvedContainerConfig c, HostConstraints host) {
        return new ConfigValidator(host).validate(c);
    }

    @Test
    void compatibleConfigPasses() {
        ValidationResult r = validate(base(), defaultHost);
        assertTrue(r.valid(), () -> r.issues().toString());
    }

    @Test
    void unsupportedArchitectureIsRejected() {
        ResolvedContainerConfig c = base().toBuilder().imageArch("arm64").build();
        ValidationResult r = validate(c, defaultHost);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(i -> i.code().equals("ARCH_UNSUPPORTED")));
    }

    @Test
    void explicitPlatformMustMatchImage() {
        ResolvedContainerConfig c = base().toBuilder().platform("linux/arm64").build();
        ValidationResult r = validate(c, defaultHost);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(i -> i.code().equals("PLATFORM_MISMATCH")));
    }

    @Test
    void duplicateHostEndpointWithinConfigIsRejected() {
        ResolvedContainerConfig c = base().toBuilder()
                .ports(List.of(
                        new PortBinding("0.0.0.0", 8080, 80, "tcp"),
                        new PortBinding("0.0.0.0", 8080, 81, "tcp")))
                .build();
        ValidationResult r = validate(c, defaultHost);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(i -> i.code().equals("PORT_CONFLICT")));
    }

    @Test
    void occupiedHostPortIsRejected() {
        HostConstraints host = new HostConstraints("linux", Set.of("amd64"),
                Set.of("0.0.0.0:8080/tcp"), false, false);
        ResolvedContainerConfig c = base().toBuilder()
                .ports(List.of(new PortBinding("0.0.0.0", 8080, 80, "tcp")))
                .build();
        ValidationResult r = validate(c, host);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(i -> i.code().equals("PORT_IN_USE")));
    }

    @Test
    void samePortDifferentProtocolIsAllowed() {
        ResolvedContainerConfig c = base().toBuilder()
                .ports(List.of(
                        new PortBinding("0.0.0.0", 53, 53, "tcp"),
                        new PortBinding("0.0.0.0", 53, 53, "udp")))
                .build();
        ValidationResult r = validate(c, defaultHost);
        assertTrue(r.valid(), () -> r.issues().toString());
    }

    @Test
    void duplicateMountTargetIsRejected() {
        ResolvedContainerConfig c = base().toBuilder()
                .mounts(List.of(
                        new Mount("/a", "/data", false, "bind"),
                        new Mount("/b", "/data", false, "bind")))
                .build();
        ValidationResult r = validate(c, defaultHost);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(i -> i.code().equals("MOUNT_TARGET_CONFLICT")));
    }

    @Test
    void writableProcMountIsRejected() {
        ResolvedContainerConfig c = base().toBuilder()
                .mounts(List.of(new Mount("/proc", "/proc", false, "bind")))
                .build();
        ValidationResult r = validate(c, defaultHost);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(i -> i.code().equals("MOUNT_PROTECTED")));
    }

    @Test
    void readOnlyProcMountIsAllowed() {
        ResolvedContainerConfig c = base().toBuilder()
                .mounts(List.of(new Mount("/proc", "/proc", true, "bind")))
                .build();
        ValidationResult r = validate(c, defaultHost);
        assertTrue(r.valid(), () -> r.issues().toString());
    }

    @Test
    void privilegedRejectedByPlatformPolicy() {
        HostConstraints host = new HostConstraints("linux", Set.of("amd64"),
                Set.of(), true, false);
        ResolvedContainerConfig c = base().toBuilder().privileged(true).build();
        ValidationResult r = validate(c, host);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(i -> i.code().equals("PRIVILEGED_FORBIDDEN")));
    }

    @Test
    void privilegedWithReadOnlyRootfsProducesWarningOnly() {
        ResolvedContainerConfig c = base().toBuilder()
                .privileged(true).readOnlyRootfs(true).build();
        ValidationResult r = validate(c, defaultHost);
        assertTrue(r.valid());
        assertTrue(r.warnings().stream()
                .anyMatch(i -> i.code().equals("PRIVILEGED_WITH_READONLY_ROOTFS")));
    }

    @Test
    void missingEntrypointProducesWarningNotError() {
        ResolvedContainerConfig c = ResolvedContainerConfig.builder()
                .imageOs("linux").imageArch("amd64").build();
        ValidationResult r = validate(c, defaultHost);
        assertTrue(r.valid());
        assertTrue(r.warnings().stream().anyMatch(i -> i.code().equals("NO_ENTRYPOINT")));
    }

    @Test
    void errorIssuesCarryExplainableMessages() {
        ResolvedContainerConfig c = base().toBuilder().imageArch("mips").build();
        ValidationIssue issue = validate(c, defaultHost).errors().get(0);
        assertEquals("image.architecture", issue.path());
        assertFalse(issue.message().isBlank());
    }
}
