package com.github.huchangjun118.containerconfigresolverjava.config;

import com.github.huchangjun118.containerconfigresolverjava.config.model.ContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.model.PortMapping;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ValidationResult;
import com.github.huchangjun118.containerconfigresolverjava.config.model.VolumeMount;
import com.github.huchangjun118.containerconfigresolverjava.config.validation.CompatibilityValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityValidatorTest {

    private final CompatibilityValidator validator = new CompatibilityValidator();

    @Test
    void validConfigPasses() {
        ContainerConfig c = ContainerConfig.builder()
                .platform("linux/amd64")
                .arch("amd64")
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "8080"))
                .ports(List.of(new PortMapping(8080, 8080, "tcp")))
                .volumes(List.of(new VolumeMount("data", "/data", false)))
                .build();
        assertTrue(validator.validate(c).valid());
    }

    @Test
    void unsupportedPlatformRejected() {
        ContainerConfig c = ContainerConfig.builder()
                .platform("linux/386")
                .entrypoint(List.of("/bin/sh"))
                .build();
        ValidationResult r = validator.validate(c);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("unsupported platform")));
    }

    @Test
    void archConflictsWithPlatformRejected() {
        ContainerConfig c = ContainerConfig.builder()
                .platform("linux/amd64")
                .arch("arm64")
                .entrypoint(List.of("/bin/sh"))
                .build();
        ValidationResult r = validator.validate(c);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("conflicts with platform")));
    }

    @Test
    void duplicateHostPortRejected() {
        ContainerConfig c = ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh"))
                .ports(List.of(
                        new PortMapping(8080, 8080, "tcp"),
                        new PortMapping(8080, 9090, "tcp")))
                .build();
        ValidationResult r = validator.validate(c);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("host port conflict")));
    }

    @Test
    void portOutOfRangeRejected() {
        ContainerConfig c = ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh"))
                .ports(List.of(new PortMapping(70000, 80, "tcp")))
                .build();
        ValidationResult r = validator.validate(c);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("out of range")));
    }

    @Test
    void duplicateVolumeTargetRejected() {
        ContainerConfig c = ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh"))
                .volumes(List.of(
                        new VolumeMount("a", "/data", false),
                        new VolumeMount("b", "/data", true)))
                .build();
        ValidationResult r = validator.validate(c);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("mounted multiple times")));
    }

    @Test
    void relativeVolumeTargetRejected() {
        ContainerConfig c = ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh"))
                .volumes(List.of(new VolumeMount("a", "data", false)))
                .build();
        ValidationResult r = validator.validate(c);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("absolute path")));
    }

    @Test
    void privilegedWithReadOnlyRootfsRejected() {
        ContainerConfig c = ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh"))
                .privileged(true)
                .readOnlyRootFilesystem(true)
                .build();
        ValidationResult r = validator.validate(c);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("incompatible")));
    }

    @Test
    void emptyEntrypointAndCommandRejected() {
        ContainerConfig c = ContainerConfig.builder()
                .platform("linux/amd64")
                .build();
        ValidationResult r = validator.validate(c);
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("entrypoint and command")));
    }

    @Test
    void multipleErrorsAllReported() {
        ContainerConfig c = ContainerConfig.builder()
                .platform("plan9/sparc")
                .ports(List.of(new PortMapping(99999, 80, "tcp")))
                .build();
        ValidationResult r = validator.validate(c);
        assertFalse(r.valid());
        // 多个冲突原因都应给出，而不是只报第一个
        assertTrue(r.errors().size() >= 2);
    }
}
