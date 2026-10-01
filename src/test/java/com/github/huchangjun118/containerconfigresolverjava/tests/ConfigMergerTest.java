package com.github.huchangjun118.containerconfigresolverjava.tests;

import com.github.huchangjun118.containerconfigresolverjava.merge.ConfigMerger;
import com.github.huchangjun118.containerconfigresolverjava.model.ConfigSourceVersion;
import com.github.huchangjun118.containerconfigresolverjava.model.FieldOrigin;
import com.github.huchangjun118.containerconfigresolverjava.model.ImageMetadata;
import com.github.huchangjun118.containerconfigresolverjava.model.Mount;
import com.github.huchangjun118.containerconfigresolverjava.model.PortBinding;
import com.github.huchangjun118.containerconfigresolverjava.model.RawContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolvedContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.model.SourceLayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMergerTest {

    private final ConfigMerger merger = new ConfigMerger();

    private ConfigSourceVersion.LayeredSource layer(SourceLayer l, String name, RawContainerConfig c) {
        return new ConfigSourceVersion.LayeredSource(l, name, c);
    }

    @Test
    void scalarFieldsFollowLayerPriority() {
        ImageMetadata image = new ImageMetadata("nginx:1.27", "linux", "amd64", null, null);
        RawContainerConfig template = RawContainerConfig.builder()
                .user("appuser").workingDir("/srv").privileged(false).build();
        RawContainerConfig env = RawContainerConfig.builder().user("envuser").build();
        RawContainerConfig service = RawContainerConfig.builder()
                .user("svcuser").privileged(true).build();
        RawContainerConfig runtime = RawContainerConfig.builder().user("rtuser").build();

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1", image,
                List.of(layer(SourceLayer.TEMPLATE, "base", template),
                        layer(SourceLayer.ENVIRONMENT, "prod", env),
                        layer(SourceLayer.SERVICE, "web", service),
                        layer(SourceLayer.RUNTIME, "inject", runtime))));

        assertEquals("rtuser", r.getUser());
        assertEquals(SourceLayer.RUNTIME, r.originOf("user").layer());
        assertEquals("/srv", r.getWorkingDir());
        assertEquals(SourceLayer.TEMPLATE, r.originOf("workingDir").layer());
        assertTrue(r.isPrivileged());
        assertEquals(SourceLayer.SERVICE, r.originOf("privileged").layer());
        assertEquals("nginx:1.27", r.getImage());
        assertEquals(SourceLayer.IMAGE, r.originOf("image").layer());
    }

    @Test
    void envMergesAtKeyLevel() {
        RawContainerConfig template = RawContainerConfig.builder()
                .env(Map.of("A", "tpl-a", "B", "tpl-b")).build();
        RawContainerConfig service = RawContainerConfig.builder()
                .env(Map.of("B", "svc-b", "C", "svc-c")).build();

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1",
                ImageMetadata.empty(),
                List.of(layer(SourceLayer.TEMPLATE, "base", template),
                        layer(SourceLayer.SERVICE, "web", service))));

        assertEquals(Map.of("A", "tpl-a", "B", "svc-b", "C", "svc-c"), r.getEnv());
        assertEquals(SourceLayer.TEMPLATE, r.originOf("env.A").layer());
        assertEquals(SourceLayer.SERVICE, r.originOf("env.B").layer());
        assertEquals(SourceLayer.SERVICE, r.originOf("env.C").layer());
    }

    @Test
    void portsAndMountsAppendAndDeduplicate() {
        RawContainerConfig template = RawContainerConfig.builder()
                .ports(List.of(new PortBinding("0.0.0.0", 80, 8080, "tcp")))
                .mounts(List.of(new Mount("/host/data", "/data", false, "bind")))
                .capabilities(List.of("NET_BIND_SERVICE"))
                .build();
        RawContainerConfig service = RawContainerConfig.builder()
                .ports(List.of(new PortBinding("0.0.0.0", 80, 8080, "tcp"),
                        new PortBinding("0.0.0.0", 443, 8443, "tcp")))
                .mounts(List.of(new Mount("/host/logs", "/logs", true, "bind")))
                .capabilities(List.of("NET_BIND_SERVICE", "SYS_TIME"))
                .build();

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1",
                ImageMetadata.empty(),
                List.of(layer(SourceLayer.TEMPLATE, "base", template),
                        layer(SourceLayer.SERVICE, "web", service))));

        assertEquals(2, r.getPorts().size());
        assertEquals(2, r.getMounts().size());
        assertEquals(List.of("NET_BIND_SERVICE", "SYS_TIME"), r.getCapabilities());
        assertEquals(SourceLayer.SERVICE, r.originOf("ports[1]").layer());
    }

    @Test
    void mountOnSameTargetIsWhollyOverriddenByHigherLayer() {
        RawContainerConfig template = RawContainerConfig.builder()
                .mounts(List.of(new Mount("/old", "/data", false, "bind"))).build();
        RawContainerConfig service = RawContainerConfig.builder()
                .mounts(List.of(new Mount("/new", "/data", true, "bind"))).build();

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1",
                ImageMetadata.empty(),
                List.of(layer(SourceLayer.TEMPLATE, "base", template),
                        layer(SourceLayer.SERVICE, "web", service))));

        assertEquals(1, r.getMounts().size());
        Mount m = r.getMounts().get(0);
        assertEquals("/new", m.source());
        assertTrue(m.readOnly());
        assertEquals(SourceLayer.SERVICE, r.originOf("mounts[0]").layer());
    }

    @Test
    void imageEntrypointAndCmdAreInheritedByDefault() {
        ImageMetadata image = new ImageMetadata("img", "linux", "amd64",
                List.of("/docker-entrypoint.sh"), List.of("serve"));

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1", image, List.of()));

        assertEquals(List.of("/docker-entrypoint.sh"), r.getEntrypoint());
        assertEquals(List.of("serve"), r.getCommand());
        assertEquals(SourceLayer.IMAGE, r.originOf("entrypoint").layer());
        assertEquals(SourceLayer.IMAGE, r.originOf("command").layer());
    }

    @Test
    void overridingCommandKeepsImageEntrypoint() {
        ImageMetadata image = new ImageMetadata("img", "linux", "amd64",
                List.of("/ep"), List.of("old-cmd"));
        RawContainerConfig service = RawContainerConfig.builder()
                .command(List.of("new-cmd")).build();

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1", image,
                List.of(layer(SourceLayer.SERVICE, "web", service))));

        assertEquals(List.of("/ep"), r.getEntrypoint());
        assertEquals(List.of("new-cmd"), r.getCommand());
    }

    @Test
    void overridingEntrypointResetsImageCommand() {
        ImageMetadata image = new ImageMetadata("img", "linux", "amd64",
                List.of("/old-ep"), List.of("image-cmd"));
        RawContainerConfig template = RawContainerConfig.builder()
                .entrypoint(List.of("/new-ep")).build();

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1", image,
                List.of(layer(SourceLayer.TEMPLATE, "base", template))));

        assertEquals(List.of("/new-ep"), r.getEntrypoint());
        assertTrue(r.getCommand().isEmpty(), "镜像 CMD 必须在 entrypoint 被覆盖时重置");
        FieldOrigin co = r.originOf("command");
        assertEquals(SourceLayer.TEMPLATE, co.layer());
        assertTrue(co.detail().contains("重置"));
    }

    @Test
    void sameLayerOverridingEntrypointAndCommandUsesBoth() {
        ImageMetadata image = new ImageMetadata("img", "linux", "amd64",
                List.of("/old-ep"), List.of("image-cmd"));
        RawContainerConfig service = RawContainerConfig.builder()
                .entrypoint(List.of("/new-ep"))
                .command(List.of("--debug"))
                .build();

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1", image,
                List.of(layer(SourceLayer.SERVICE, "web", service))));

        assertEquals(List.of("/new-ep"), r.getEntrypoint());
        assertEquals(List.of("--debug"), r.getCommand());
        assertNull(r.originOf("command").detail());
    }

    @Test
    void explicitEmptyEntrypointClearsInheritedEntrypoint() {
        ImageMetadata image = new ImageMetadata("img", "linux", "amd64",
                List.of("/ep"), List.of("cmd"));
        RawContainerConfig service = RawContainerConfig.builder()
                .entrypoint(List.of())
                .command(List.of("/bin/sh", "-c", "echo hi"))
                .build();

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1", image,
                List.of(layer(SourceLayer.SERVICE, "web", service))));

        assertTrue(r.getEntrypoint().isEmpty());
        assertEquals(List.of("/bin/sh", "-c", "echo hi"), r.getCommand());
    }

    @Test
    void absentFieldsDoNotOverrideLowerLayers() {
        RawContainerConfig template = RawContainerConfig.builder().workingDir("/a").build();
        RawContainerConfig service = RawContainerConfig.builder().readOnlyRootfs(false).build();

        ResolvedContainerConfig r = merger.merge(new ConfigSourceVersion("v1",
                ImageMetadata.empty(),
                List.of(layer(SourceLayer.TEMPLATE, "base", template),
                        layer(SourceLayer.SERVICE, "web", service))));

        assertEquals("/a", r.getWorkingDir());
        assertFalse(r.isReadOnlyRootfs());
    }
}
