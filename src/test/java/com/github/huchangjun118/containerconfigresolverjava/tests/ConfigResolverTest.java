package com.github.huchangjun118.containerconfigresolverjava.tests;

import com.github.huchangjun118.containerconfigresolverjava.interpolation.VariableInterpolator;
import com.github.huchangjun118.containerconfigresolverjava.merge.ConfigMerger;
import com.github.huchangjun118.containerconfigresolverjava.model.CommittedSnapshot;
import com.github.huchangjun118.containerconfigresolverjava.model.ConfigSourceVersion;
import com.github.huchangjun118.containerconfigresolverjava.model.ImageMetadata;
import com.github.huchangjun118.containerconfigresolverjava.model.PortBinding;
import com.github.huchangjun118.containerconfigresolverjava.model.RawContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolveFailure;
import com.github.huchangjun118.containerconfigresolverjava.model.SourceLayer;
import com.github.huchangjun118.containerconfigresolverjava.resolver.ConfigResolver;
import com.github.huchangjun118.containerconfigresolverjava.validation.ConfigValidator;
import com.github.huchangjun118.containerconfigresolverjava.validation.HostConstraints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigResolverTest {

    private ConfigResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ConfigResolver(new ConfigMerger(), new VariableInterpolator(),
                new ConfigValidator(HostConstraints.linuxAmd64(Set.of(9090))));
    }

    private ConfigSourceVersion goodVersion(String revision) {
        return new ConfigSourceVersion(revision,
                new ImageMetadata("nginx:1.27", "linux", "amd64",
                        List.of("/ep"), List.of("cmd")),
                List.of(new ConfigSourceVersion.LayeredSource(
                        SourceLayer.SERVICE, "web",
                        RawContainerConfig.builder().user("u").build())));
    }

    private ConfigSourceVersion invalidPortVersion(String revision) {
        return new ConfigSourceVersion(revision,
                new ImageMetadata("nginx:1.27", "linux", "amd64",
                        List.of("/ep"), List.of("cmd")),
                List.of(new ConfigSourceVersion.LayeredSource(
                        SourceLayer.SERVICE, "web",
                        RawContainerConfig.builder()
                                .ports(List.of(new PortBinding("0.0.0.0", 9090, 80, "tcp")))
                                .build())));
    }

    private ConfigSourceVersion badInterpolationVersion(String revision) {
        return new ConfigSourceVersion(revision,
                new ImageMetadata("nginx:1.27", "linux", "amd64",
                        List.of("/ep"), List.of("cmd")),
                List.of(new ConfigSourceVersion.LayeredSource(
                        SourceLayer.SERVICE, "web",
                        RawContainerConfig.builder()
                                .env(Map.of("V", "${UNDEFINED}"))
                                .build())));
    }

    @Test
    void firstSuccessfulReloadPublishesSnapshot() {
        Optional<CommittedSnapshot> snap = resolver.reload(goodVersion("r1"), Map.of());
        assertTrue(snap.isPresent());
        assertEquals("r1", snap.get().revision());
        assertTrue(resolver.current().isPresent());
        assertTrue(resolver.lastFailure().isEmpty());
        assertNotNull(snap.get().versionId());
        assertEquals(64, snap.get().versionId().length());
    }

    @Test
    void validationFailureDoesNotPollutePreviousSnapshot() {
        resolver.reload(goodVersion("r1"), Map.of());
        CommittedSnapshot before = resolver.current().orElseThrow();

        Optional<CommittedSnapshot> failed = resolver.reload(invalidPortVersion("r2"), Map.of());
        assertTrue(failed.isEmpty());

        // 旧版本仍生效，且字段不变
        CommittedSnapshot after = resolver.current().orElseThrow();
        assertEquals("r1", after.revision());
        assertEquals(before.versionId(), after.versionId());

        ResolveFailure failure = resolver.lastFailure().orElseThrow();
        assertEquals("r2", failure.revision());
        assertEquals("VALIDATE", failure.phase());
        assertTrue(failure.message().contains("PORT_IN_USE"));
    }

    @Test
    void interpolationFailureIsIsolatedWithDistinguishablePhase() {
        resolver.reload(goodVersion("r1"), Map.of());
        Optional<CommittedSnapshot> failed = resolver.reload(badInterpolationVersion("r2"), Map.of());
        assertTrue(failed.isEmpty());
        assertEquals("r1", resolver.current().orElseThrow().revision());

        ResolveFailure failure = resolver.lastFailure().orElseThrow();
        assertEquals("INTERPOLATE", failure.phase());
        assertEquals("env.V", failure.path());
    }

    @Test
    void repeatedFailuresKeepServiceAvailable() {
        resolver.reload(goodVersion("r1"), Map.of());
        for (int i = 0; i < 5; i++) {
            assertTrue(resolver.reload(invalidPortVersion("bad-" + i), Map.of()).isEmpty());
            assertTrue(resolver.current().isPresent(), "连续失败后旧快照必须仍可读");
        }
        // 一旦输入恢复合法，可以再次成功发布
        assertTrue(resolver.reload(goodVersion("r2"), Map.of()).isPresent());
        assertEquals("r2", resolver.current().orElseThrow().revision());
        assertTrue(resolver.lastFailure().isEmpty(), "成功提交后失败记录应清空");
    }

    @Test
    void sourceProviderFailureIsIsolated() {
        resolver.reload(goodVersion("r1"), Map.of());
        Optional<CommittedSnapshot> r = resolver.reloadFrom(
                () -> { throw new IllegalStateException("来源存储不可达"); }, Map.of());
        assertTrue(r.isEmpty());
        assertEquals("r1", resolver.current().orElseThrow().revision());
        ResolveFailure f = resolver.lastFailure().orElseThrow();
        assertEquals("SOURCE", f.phase());
        assertTrue(f.message().contains("来源存储不可达"));
    }

    @Test
    void sameInputsProduceIdenticalVersionIdAcrossInstances() {
        ConfigResolver other = new ConfigResolver(new ConfigMerger(),
                new VariableInterpolator(),
                new ConfigValidator(HostConstraints.linuxAmd64(Set.of(9090))));

        String id1 = resolver.reload(goodVersion("rev-A"), Map.of()).orElseThrow().versionId();
        String id2 = other.reload(goodVersion("rev-B"), Map.of()).orElseThrow().versionId();
        // revision 不同但定义相同 => 指纹必须相同
        assertEquals(id1, id2);
    }

    @Test
    void everyFinalFieldHasTraceableOrigin() {
        CommittedSnapshot snap = resolver.reload(goodVersion("r1"), Map.of()).orElseThrow();
        var origins = snap.resolved().getOrigins();
        assertEquals(SourceLayer.IMAGE, origins.get("image").layer());
        assertEquals(SourceLayer.IMAGE, origins.get("entrypoint").layer());
        assertEquals(SourceLayer.IMAGE, origins.get("entrypoint[0]").layer());
        assertEquals(SourceLayer.IMAGE, origins.get("command[0]").layer());
        assertEquals(SourceLayer.SERVICE, origins.get("user").layer());
        assertEquals("web", origins.get("user").source());
    }

    @Test
    void emptyBeforeAnySuccessfulCommit() {
        assertFalse(resolver.current().isPresent());
        assertFalse(resolver.lastFailure().isPresent());
    }
}
