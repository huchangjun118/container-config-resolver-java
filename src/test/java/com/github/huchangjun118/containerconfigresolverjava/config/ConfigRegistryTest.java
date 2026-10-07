package com.github.huchangjun118.containerconfigresolverjava.config;

import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.Interpolator;
import com.github.huchangjun118.containerconfigresolverjava.config.merge.ConfigMerger;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ConfigSource;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ResolvedConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.model.SourcePriority;
import com.github.huchangjun118.containerconfigresolverjava.config.registry.ConfigRegistry;
import com.github.huchangjun118.containerconfigresolverjava.config.registry.ConfigRegistry.ReloadResult;
import com.github.huchangjun118.containerconfigresolverjava.config.validation.CompatibilityValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigRegistryTest {

    private ConfigRegistry registry(java.util.function.Supplier<List<ConfigSource>> provider) {
        return new ConfigRegistry(provider::get, Map::of,
                new ConfigMerger(), new Interpolator(), new CompatibilityValidator());
    }

    private ConfigSource validSource(String id, String image) {
        return new ConfigSource(id, id, SourcePriority.BASE_TEMPLATE,
                ContainerConfig.builder()
                        .image(image)
                        .platform("linux/amd64")
                        .entrypoint(List.of("/app/server"))
                        .command(List.of("--port", "8080"))
                        .env(Map.of("LOG_LEVEL", "info"))
                        .build());
    }

    @Test
    void beforeFirstCommit_readReturnsNull() {
        ConfigRegistry registry = registry(() -> List.of(validSource("base", "img:1")));
        assertNull(registry.current());
        assertEquals(0, registry.currentVersion());
    }

    @Test
    void successfulReload_commitsVersionWithProvenance() {
        ConfigRegistry registry = registry(() -> List.of(validSource("base", "img:1")));
        ReloadResult result = registry.reload();
        assertTrue(result instanceof ReloadResult.Success);
        ResolvedConfig c = registry.current();
        assertNotNull(c);
        assertEquals(1, c.version());
        assertEquals("img:1", c.config().image());
        assertNotNull(c.provenance().get("image"));
        assertEquals("base", c.provenance().get("image").sourceId());
        assertTrue(c.sources().contains("base"));
    }

    @Test
    void failedReload_keepsPreviousVersionAndRecordsFailure() {
        AtomicBoolean broken = new AtomicBoolean(false);
        ConfigRegistry registry = registry(() -> {
            if (broken.get()) {
                return List.of(new ConfigSource("base", "base", SourcePriority.BASE_TEMPLATE,
                        ContainerConfig.builder()
                                .image("img:bad")
                                .platform("linux/amd64")
                                .entrypoint(List.of("/app/server"))
                                .env(Map.of("BROKEN", "${env.NO_SUCH_KEY}"))
                                .build()));
            }
            return List.of(validSource("base", "img:1"));
        });

        registry.reload();
        long v1 = registry.currentVersion();
        assertEquals(1, v1);

        broken.set(true);
        ReloadResult result = registry.reload();
        assertTrue(result instanceof ReloadResult.Failure);

        // 上一版本继续生效，不被污染
        assertEquals(v1, registry.currentVersion());
        assertEquals("img:1", registry.current().config().image());
        // 失败原因可查询
        assertNotNull(registry.lastFailure());
        assertTrue(registry.lastFailure().reason().contains("NO_SUCH_KEY"));
        assertEquals(1, registry.lastFailure().consecutiveFailures());
        assertEquals(1, registry.consecutiveFailures());
    }

    @Test
    void consecutiveFailures_doNotBreakService_andRecoveryResetsCounter() {
        AtomicBoolean broken = new AtomicBoolean(true);
        ConfigRegistry registry = registry(() -> {
            if (broken.get()) {
                return List.of(new ConfigSource("base", "base", SourcePriority.BASE_TEMPLATE,
                        ContainerConfig.builder().image("img:bad").platform("linux/amd64").build()));
            }
            return List.of(validSource("base", "img:recovered"));
        });

        // 连续失败：服务不崩溃，读取始终为 null（无已提交版本）但不抛异常
        for (int i = 0; i < 5; i++) {
            ReloadResult r = registry.reload();
            assertTrue(r instanceof ReloadResult.Failure);
        }
        assertEquals(5, registry.consecutiveFailures());
        assertNotNull(registry.lastFailure());

        // 恢复：下一次成功重载提交新版本，失败计数清零
        broken.set(false);
        ReloadResult r = registry.reload();
        assertTrue(r instanceof ReloadResult.Success);
        // 恢复成功后连续失败计数清零，失败记录清除
        assertEquals(0, registry.consecutiveFailures());
        assertNull(registry.lastFailure());
        assertEquals("img:recovered", registry.current().config().image());
    }

    @Test
    void readsAreConsistentSnapshots_underConcurrentReloads() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        ConfigRegistry registry = registry(() -> {
            int n = counter.incrementAndGet();
            return List.of(validSource("base", "img:" + n));
        });

        int readerCount = 8;
        int iterations = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean inconsistent = new AtomicBoolean(false);

        // 读者：每次读取都必须是某个已提交的自洽版本（image 与 version 对应）
        Future<?>[] readers = new Future[readerCount];
        for (int i = 0; i < readerCount; i++) {
            readers[i] = pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < iterations; k++) {
                        ResolvedConfig c = registry.current();
                        if (c != null) {
                            String expected = "img:" + c.version();
                            if (!expected.equals(c.config().image())) {
                                inconsistent.set(true);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 写者：持续重载
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int k = 0; k < 500; k++) {
                    registry.reload();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<?> r : readers) {
            r.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 任一时刻读到的都是自洽单版本，无字段级混合
        assertTrue(!inconsistent.get(), "read observed a mixed/non-committed version");
        // 每次成功重载都推进了版本
        assertEquals(500, registry.currentVersion());
        assertEquals("img:500", registry.current().config().image());
    }

    @Test
    void slowReader_doesNotBlockReload() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        ConfigRegistry registry = registry(() -> {
            int n = counter.incrementAndGet();
            return List.of(validSource("base", "img:" + n));
        });
        registry.reload();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch readerHolding = new CountDownLatch(1);
        CountDownLatch reloadDone = new CountDownLatch(1);
        AtomicReference<ResolvedConfig> snapshot = new AtomicReference<>();

        Future<?> slowReader = pool.submit(() -> {
            ResolvedConfig c = registry.current();
            snapshot.set(c);
            readerHolding.countDown();
            try {
                Thread.sleep(300); // 慢读者持有旧快照
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        readerHolding.await();
        Future<?> reload = pool.submit(() -> {
            registry.reload();
            reloadDone.countDown();
        });

        // 重载不得被慢读者阻塞
        assertTrue(reloadDone.await(2, TimeUnit.SECONDS));
        reload.get(2, TimeUnit.SECONDS);
        slowReader.get(2, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 慢读者持有的是旧版本快照，注册中心已推进到新版本，两者互不阻塞
        assertEquals(1, snapshot.get().version());
        assertEquals(2, registry.currentVersion());
    }

    @Test
    void sameInputs_produceDeterministicResult() {
        ConfigRegistry r1 = registry(() -> List.of(validSource("base", "img:1")));
        ConfigRegistry r2 = registry(() -> List.of(validSource("base", "img:1")));
        r1.reload();
        r2.reload();
        assertEquals(r1.current().config().image(), r2.current().config().image());
        assertEquals(r1.current().config().env(), r2.current().config().env());
        assertEquals(r1.current().config().entrypoint(), r2.current().config().entrypoint());
        assertEquals(r1.current().provenance(), r2.current().provenance());
    }

    @Test
    void readsNeverObserveCrossLayerEntrypointCommandMixing_underConcurrentReloads() throws Exception {
        // 每次重载两份来源固定不变：镜像默认 E0+C0；服务层只给入口点 E1（命令耦合重置）。
        // 若读到来路不明的组合（如 E1 + C0），说明出现了跨层拼接或字段级中间态。
        java.util.function.Supplier<List<ConfigSource>> provider = () -> {
            ConfigSource base = new ConfigSource("base", "base", SourcePriority.BASE_TEMPLATE,
                    ContainerConfig.builder()
                            .image("img")
                            .platform("linux/amd64")
                            .entrypoint(List.of("/img/default-entry"))
                            .command(List.of("--img-default-arg"))
                            .build());
            ConfigSource svc = new ConfigSource("svc", "svc", SourcePriority.SERVICE,
                    ContainerConfig.builder()
                            .entrypoint(List.of("/svc/entry"))
                            .build());
            return List.of(svc, base);
        };
        ConfigRegistry registry = registry(provider);

        int readerCount = 8;
        int iterations = 2000;
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean inconsistent = new AtomicBoolean(false);

        Future<?>[] readers = new Future<?>[readerCount];
        for (int i = 0; i < readerCount; i++) {
            readers[i] = pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < iterations; k++) {
                        ResolvedConfig c = registry.current();
                        if (c == null) {
                            continue;
                        }
                        var cfg = c.config();
                        boolean committedPair =
                                cfg.entrypoint().equals(List.of("/svc/entry"))
                                        && cfg.command().equals(List.of());
                        if (!committedPair) {
                            inconsistent.set(true);
                        }
                        // 入口点与命令的来源必须自洽：命令为 svc 层入口点触发的耦合重置
                        var cmdProv = c.provenance().get("command");
                        if (cmdProv == null
                                || !"svc".equals(cmdProv.sourceId())
                                || cmdProv.kind() != com.github.huchangjun118.containerconfigresolverjava
                                        .config.model.Provenance.Kind.COUPLED_RESET) {
                            inconsistent.set(true);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
                for (int k = 0; k < 500; k++) {
                    registry.reload();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        for (Future<?> r : readers) {
            r.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertTrue(!inconsistent.get(), "read observed a cross-layer mixed entrypoint/command pair");
        assertEquals(List.of("/svc/entry"), registry.current().config().entrypoint());
        assertEquals(List.of(), registry.current().config().command());
    }

    @Test
    void invalidSyntaxReload_isReportedAndIsolatedAndServiceKeepsServing() {
        AtomicBoolean broken = new AtomicBoolean(false);
        ConfigRegistry registry = registry(() -> {
            if (broken.get()) {
                return List.of(new ConfigSource("base", "base", SourcePriority.BASE_TEMPLATE,
                        ContainerConfig.builder()
                                .image("img:bad$") // 悬空 $：写法非法
                                .platform("linux/amd64")
                                .entrypoint(List.of("/app/server"))
                                .build()));
            }
            return List.of(validSource("base", "img:1"));
        });

        ReloadResult first = registry.reload();
        assertTrue(first instanceof ReloadResult.Success);

        // 多次用非法写法的来源重载：全部失败、原因可查，但上一版本持续生效
        broken.set(true);
        for (int i = 0; i < 5; i++) {
            ReloadResult r = registry.reload();
            assertTrue(r instanceof ReloadResult.Failure);
            ResolvedConfig c = registry.current();
            assertNotNull(c);
            assertEquals("img:1", c.config().image()); // 读取始终拿到已提交版本
        }
        assertEquals(5, registry.consecutiveFailures());
        assertNotNull(registry.lastFailure());
        // 错误原因可区分到“写法非法”这一类
        assertTrue(registry.lastFailure().reason().contains("InvalidSyntaxException"),
                "failure reason should expose syntax error category: "
                        + registry.lastFailure().reason());

        // 恢复：服务无需重启即可继续提交新版本
        broken.set(false);
        ReloadResult recovered = registry.reload();
        assertTrue(recovered instanceof ReloadResult.Success);
        assertEquals(0, registry.consecutiveFailures());
        assertNull(registry.lastFailure());
    }
}
