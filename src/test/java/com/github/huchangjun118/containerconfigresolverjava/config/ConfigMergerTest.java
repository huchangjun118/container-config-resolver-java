package com.github.huchangjun118.containerconfigresolverjava.config;

import com.github.huchangjun118.containerconfigresolverjava.config.merge.ConfigMerger;
import com.github.huchangjun118.containerconfigresolverjava.config.merge.ConfigMerger.MergeOutcome;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ConfigSource;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.model.PortMapping;
import com.github.huchangjun118.containerconfigresolverjava.config.model.Provenance;
import com.github.huchangjun118.containerconfigresolverjava.config.model.SourcePriority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigMergerTest {

    private final ConfigMerger merger = new ConfigMerger();

    private ConfigSource source(String id, SourcePriority p, ContainerConfig.Builder b) {
        return new ConfigSource(id, id, p, b.build());
    }

    @Test
    void mergesByPriority_fieldLevelForMaps_replaceForScalarsAndLists() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .image("img:1")
                .platform("linux/amd64")
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "8080"))
                .env(Map.of("LOG_LEVEL", "info", "A", "1"))
                .ports(List.of(new PortMapping(8080, 8080, "tcp"))));
        ConfigSource env = source("env", SourcePriority.ENVIRONMENT, ContainerConfig.builder()
                .env(Map.of("LOG_LEVEL", "warn", "TZ", "UTC")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .env(Map.of("SERVICE_NAME", "order"))
                .ports(List.of(new PortMapping(9090, 9090, "tcp"))));
        ConfigSource runtime = source("runtime", SourcePriority.RUNTIME_INJECTION, ContainerConfig.builder()
                .image("img:2"));

        MergeOutcome outcome = merger.merge(List.of(runtime, svc, env, base)); // 乱序传入
        ContainerConfig c = outcome.config();

        // 标量：高优先级替换
        assertEquals("img:2", c.image());
        assertEquals("linux/amd64", c.platform());
        // 映射：字段级合并，低优先级键保留
        assertEquals("warn", c.env().get("LOG_LEVEL"));
        assertEquals("1", c.env().get("A"));
        assertEquals("UTC", c.env().get("TZ"));
        assertEquals("order", c.env().get("SERVICE_NAME"));
        // 列表：整体替换
        assertEquals(1, c.ports().size());
        assertEquals(9090, c.ports().get(0).hostPort());
        // 入口点/命令独立继承
        assertEquals(List.of("/app/server"), c.entrypoint());
        assertEquals(List.of("--port", "8080"), c.command());

        // 来源追溯
        Map<String, Provenance> prov = outcome.provenance();
        assertEquals(new Provenance("runtime", "image"), prov.get("image"));
        assertEquals(new Provenance("base", "platform"), prov.get("platform"));
        assertEquals(new Provenance("env", "env.LOG_LEVEL"), prov.get("env.LOG_LEVEL"));
        assertEquals(new Provenance("svc", "env.SERVICE_NAME"), prov.get("env.SERVICE_NAME"));
        assertEquals(new Provenance("svc", "ports"), prov.get("ports"));
        assertEquals(new Provenance("base", "entrypoint"), prov.get("entrypoint"));
    }

    @Test
    void explicitEmptyListResetsField() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "8080")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of()));

        MergeOutcome outcome = merger.merge(List.of(base, svc));
        // 显式空列表 -> 空列表（重置），而非 null
        assertEquals(List.of(), outcome.config().entrypoint());
        // 该层给出了入口点（含显式空）而未给命令 -> 命令一并重置，且可追溯为 RESET
        assertEquals(List.of(), outcome.config().command());
        assertEquals(new Provenance("svc", "command", Provenance.Kind.RESET),
                outcome.provenance().get("command"));
    }

    @Test
    void entrypointOnlyOverride_resetsCommand_notAppendsImageDefault() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "8080")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh", "-c")));

        MergeOutcome outcome = merger.merge(List.of(base, svc));
        ContainerConfig c = outcome.config();

        // 新入口点生效，旧命令不得接在新入口点后面
        assertEquals(List.of("/bin/sh", "-c"), c.entrypoint());
        assertEquals(List.of(), c.command());

        // 每个取值可追溯到来源；被重置的命令标记为 RESET
        Map<String, Provenance> prov = outcome.provenance();
        assertEquals(new Provenance("svc", "entrypoint", Provenance.Kind.SET), prov.get("entrypoint"));
        assertEquals(new Provenance("svc", "command", Provenance.Kind.RESET), prov.get("command"));
    }

    @Test
    void commandOnlyOverride_entrypointStillInherited() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "8080")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .command(List.of("--port", "9090")));

        MergeOutcome outcome = merger.merge(List.of(base, svc));
        ContainerConfig c = outcome.config();

        assertEquals(List.of("/app/server"), c.entrypoint());
        assertEquals(List.of("--port", "9090"), c.command());
        assertEquals(new Provenance("base", "entrypoint"), outcome.provenance().get("entrypoint"));
        assertEquals(new Provenance("svc", "command"), outcome.provenance().get("command"));
    }

    @Test
    void entrypointAndCommandTogether_bothTakeEffect() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "8080")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh"))
                .command(List.of("-c", "run")));

        MergeOutcome outcome = merger.merge(List.of(base, svc));
        ContainerConfig c = outcome.config();

        assertEquals(List.of("/bin/sh"), c.entrypoint());
        assertEquals(List.of("-c", "run"), c.command());
        assertEquals(new Provenance("svc", "entrypoint", Provenance.Kind.SET),
                outcome.provenance().get("entrypoint"));
        assertEquals(new Provenance("svc", "command", Provenance.Kind.SET),
                outcome.provenance().get("command"));
    }

    @Test
    void resetIsDistinguishableFromExplicitEmptyOverride() {
        // 场景一：命令被"只给入口点"的层重置 -> RESET
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "8080")));
        ConfigSource entrypointOnly = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh")));
        MergeOutcome reset = merger.merge(List.of(base, entrypointOnly));

        // 场景二：命令被显式覆盖为空列表 -> SET
        ConfigSource explicitEmpty = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .command(List.of()));
        MergeOutcome emptied = merger.merge(List.of(base, explicitEmpty));

        // 两者的值都是空列表，但来源标记必须能区分
        assertEquals(List.of(), reset.config().command());
        assertEquals(List.of(), emptied.config().command());
        assertEquals(Provenance.Kind.RESET, reset.provenance().get("command").kind());
        assertEquals(Provenance.Kind.SET, emptied.provenance().get("command").kind());
    }

    @Test
    void higherPriorityCommand_appliesAfterLowerLayerReset() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "8080")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh"))); // 触发命令重置
        ConfigSource runtime = source("runtime", SourcePriority.RUNTIME_INJECTION, ContainerConfig.builder()
                .command(List.of("run"))); // 更高优先级重新给出命令

        MergeOutcome outcome = merger.merge(List.of(base, svc, runtime));
        ContainerConfig c = outcome.config();

        assertEquals(List.of("/bin/sh"), c.entrypoint());
        assertEquals(List.of("run"), c.command());
        assertEquals(new Provenance("runtime", "command", Provenance.Kind.SET),
                outcome.provenance().get("command"));
    }

    @Test
    void entrypointCommandMerge_isDeterministic_regardlessOfInputOrder() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "8080")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of("/bin/sh")));
        ConfigSource runtime = source("runtime", SourcePriority.RUNTIME_INJECTION, ContainerConfig.builder()
                .command(List.of("run")));

        MergeOutcome r1 = merger.merge(List.of(base, svc, runtime));
        MergeOutcome r2 = merger.merge(List.of(runtime, base, svc));
        MergeOutcome r3 = merger.merge(List.of(svc, runtime, base));

        for (MergeOutcome r : List.of(r2, r3)) {
            assertEquals(r1.config().entrypoint(), r.config().entrypoint());
            assertEquals(r1.config().command(), r.config().command());
            assertEquals(r1.provenance(), r.provenance());
            assertEquals(r1.sources(), r.sources());
        }
    }

    @Test
    void mergeIsDeterministic_regardlessOfInputOrder() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .image("img:1").env(Map.of("A", "1")));
        ConfigSource env = source("env", SourcePriority.ENVIRONMENT, ContainerConfig.builder()
                .env(Map.of("A", "2", "B", "x")));

        MergeOutcome r1 = merger.merge(List.of(base, env));
        MergeOutcome r2 = merger.merge(List.of(env, base));
        assertEquals(r1.config().image(), r2.config().image());
        assertEquals(r1.config().env(), r2.config().env());
        assertEquals(r1.sources(), r2.sources());
    }
}
