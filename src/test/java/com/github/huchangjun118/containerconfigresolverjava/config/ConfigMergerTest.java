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
        // 命令未被触及，保持继承
        assertEquals(List.of("--port", "8080"), outcome.config().command());
        // 显式重置必须在来源上可见，与普通取值区分
        assertEquals(Provenance.Kind.EXPLICIT_RESET, outcome.provenance().get("entrypoint").kind());
    }

    @Test
    void entrypointOnlyOverridden_coupledResetsCommand() {
        // 镜像默认 E0 + C0；高层只给新入口点 E'，不给命令。
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/img/default-entry"))
                .command(List.of("--img-default-arg")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server")));

        MergeOutcome outcome = merger.merge(List.of(svc, base)); // 乱序传入
        ContainerConfig c = outcome.config();

        // 新入口点生效，镜像默认命令不得被拼接到新入口点后面
        assertEquals(List.of("/app/server"), c.entrypoint());
        assertEquals(List.of(), c.command());

        Map<String, Provenance> prov = outcome.provenance();
        assertEquals(new Provenance("svc", "entrypoint", Provenance.Kind.VALUE),
                prov.get("entrypoint"));
        // 命令为“耦合重置”：可看出是被同层入口点联动清空，而非来源恰好给了空命令
        assertEquals(new Provenance("svc", "entrypoint", Provenance.Kind.COUPLED_RESET),
                prov.get("command"));
    }

    @Test
    void commandOnlyOverridden_entrypointStaysInherited() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/img/default-entry"))
                .command(List.of("--img-default-arg")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .command(List.of("--port", "9090")));

        MergeOutcome outcome = merger.merge(List.of(base, svc));
        assertEquals(List.of("/img/default-entry"), outcome.config().entrypoint());
        assertEquals(List.of("--port", "9090"), outcome.config().command());
        assertEquals(new Provenance("base", "entrypoint", Provenance.Kind.VALUE),
                outcome.provenance().get("entrypoint"));
        assertEquals(new Provenance("svc", "command", Provenance.Kind.VALUE),
                outcome.provenance().get("command"));
    }

    @Test
    void entrypointAndCommandOverriddenTogether_bothApply() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/img/default-entry"))
                .command(List.of("--img-default-arg")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server"))
                .command(List.of("--port", "9090")));

        MergeOutcome outcome = merger.merge(List.of(base, svc));
        assertEquals(List.of("/app/server"), outcome.config().entrypoint());
        assertEquals(List.of("--port", "9090"), outcome.config().command());
        assertEquals(Provenance.Kind.VALUE, outcome.provenance().get("command").kind());
    }

    @Test
    void explicitEmptyCommandIsExplicitReset_notCoupledReset() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/img/default-entry"))
                .command(List.of("--img-default-arg")));
        // 同层同时给出新入口点与显式空命令：两种“空”必须可区分
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of("/app/server"))
                .command(List.of()));

        MergeOutcome outcome = merger.merge(List.of(base, svc));
        assertEquals(List.of("/app/server"), outcome.config().entrypoint());
        assertEquals(List.of(), outcome.config().command());
        assertEquals(new Provenance("svc", "command", Provenance.Kind.EXPLICIT_RESET),
                outcome.provenance().get("command"));
    }

    @Test
    void emptyEntrypointOnly_resetsEntrypointButKeepsCommand() {
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/img/default-entry"))
                .command(List.of("--img-default-arg")));
        ConfigSource svc = source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                .entrypoint(List.of()));

        MergeOutcome outcome = merger.merge(List.of(base, svc));
        assertEquals(List.of(), outcome.config().entrypoint());
        assertEquals(List.of("--img-default-arg"), outcome.config().command());
        assertEquals(Provenance.Kind.EXPLICIT_RESET, outcome.provenance().get("entrypoint").kind());
        // 命令来源仍是镜像层，未被联动
        assertEquals("base", outcome.provenance().get("command").sourceId());
    }

    @Test
    void laterCommandOnlyOverride_revivesCommandAfterCoupledReset() {
        // 三层：镜像 E0+C0 -> 环境层只给 E1（命令耦合重置）-> 运行时层只给 C2（入口点继承）
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/img/default-entry"))
                .command(List.of("--img-default-arg")));
        ConfigSource env = source("env", SourcePriority.ENVIRONMENT, ContainerConfig.builder()
                .entrypoint(List.of("/env/entry")));
        ConfigSource runtime = source("runtime", SourcePriority.RUNTIME_INJECTION, ContainerConfig.builder()
                .command(List.of("--runtime-arg")));

        MergeOutcome outcome = merger.merge(List.of(runtime, base, env));
        assertEquals(List.of("/env/entry"), outcome.config().entrypoint());
        assertEquals(List.of("--runtime-arg"), outcome.config().command());
    }

    @Test
    void laterEntrypointOnlyOverride_coupledResetsAgainstHigherCommand() {
        // 镜像 E0+C0 -> 环境层只给 C1（入口点继承）-> 运行时层只给 E2（命令再次耦合重置），
        // 不允许出现“入口点来自运行时层、命令来自环境层”的拼接。
        ConfigSource base = source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                .entrypoint(List.of("/img/default-entry"))
                .command(List.of("--img-default-arg")));
        ConfigSource env = source("env", SourcePriority.ENVIRONMENT, ContainerConfig.builder()
                .command(List.of("--env-arg")));
        ConfigSource runtime = source("runtime", SourcePriority.RUNTIME_INJECTION, ContainerConfig.builder()
                .entrypoint(List.of("/runtime/entry")));

        MergeOutcome outcome = merger.merge(List.of(runtime, env, base));
        assertEquals(List.of("/runtime/entry"), outcome.config().entrypoint());
        assertEquals(List.of(), outcome.config().command());
        assertEquals(new Provenance("runtime", "entrypoint", Provenance.Kind.COUPLED_RESET),
                outcome.provenance().get("command"));
    }

    @Test
    void coupledResetIsDeterministic_regardlessOfInputOrder() {
        List<ConfigSource> sources = List.of(
                source("base", SourcePriority.BASE_TEMPLATE, ContainerConfig.builder()
                        .entrypoint(List.of("/img/default-entry"))
                        .command(List.of("--img-default-arg"))),
                source("svc", SourcePriority.SERVICE, ContainerConfig.builder()
                        .entrypoint(List.of("/app/server"))));

        MergeOutcome r1 = merger.merge(new java.util.ArrayList<>(sources));
        java.util.List<ConfigSource> reversed = new java.util.ArrayList<>(sources);
        java.util.Collections.reverse(reversed);
        MergeOutcome r2 = merger.merge(reversed);
        assertEquals(r1.config().entrypoint(), r2.config().entrypoint());
        assertEquals(r1.config().command(), r2.config().command());
        assertEquals(r1.provenance(), r2.provenance());
        assertEquals(r1.sources(), r2.sources());
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
