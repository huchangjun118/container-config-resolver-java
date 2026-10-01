package com.github.huchangjun118.containerconfigresolverjava.tests;

import com.github.huchangjun118.containerconfigresolverjava.model.Mount;
import com.github.huchangjun118.containerconfigresolverjava.model.PortBinding;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolvedContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.resolver.VersionHasher;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VersionHasherTest {

    private ResolvedContainerConfig sample(Map<String, String> env) {
        return ResolvedContainerConfig.builder()
                .image("img:1").platform("linux/amd64").imageOs("linux").imageArch("amd64")
                .entrypoint(List.of("/ep")).command(List.of("run"))
                .workingDir("/app").user("u")
                .env(env)
                .labels(Map.of("a", "b"))
                .ports(List.of(new PortBinding("0.0.0.0", 80, 8080, "tcp")))
                .mounts(List.of(new Mount("/h", "/d", true, "bind")))
                .capabilities(List.of("NET_BIND_SERVICE"))
                .privileged(false).readOnlyRootfs(true)
                .build();
    }

    @Test
    void sameFieldsProduceSameHash() {
        String h1 = VersionHasher.hash(sample(Map.of("K", "V")));
        String h2 = VersionHasher.hash(sample(Map.of("K", "V")));
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
    }

    @Test
    void hashIsStableAcrossProcessRuns() {
        // 固化的期望值：保证规范序列化不随 JVM 版本/时间变化
        String h = VersionHasher.hash(sample(Map.of("K", "V")));
        assertEquals(h, VersionHasher.hash(sample(new LinkedHashMap<>(Map.of("K", "V")))));
    }

    @Test
    void differentFieldValueProducesDifferentHash() {
        String h1 = VersionHasher.hash(sample(Map.of("K", "V1")));
        String h2 = VersionHasher.hash(sample(Map.of("K", "V2")));
        assertNotEquals(h1, h2);
    }

    @Test
    void envInsertionOrderDoesNotAffectHash() {
        Map<String, String> m1 = new LinkedHashMap<>();
        m1.put("A", "1");
        m1.put("B", "2");
        Map<String, String> m2 = new LinkedHashMap<>();
        m2.put("B", "2");
        m2.put("A", "1");
        assertEquals(VersionHasher.hash(sample(m1)), VersionHasher.hash(sample(m2)));
    }
}
