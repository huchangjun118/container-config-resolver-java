package com.github.huchangjun118.containerconfigresolverjava.tests;

import com.github.huchangjun118.containerconfigresolverjava.interpolation.VariableInterpolator;
import com.github.huchangjun118.containerconfigresolverjava.merge.ConfigMerger;
import com.github.huchangjun118.containerconfigresolverjava.model.CommittedSnapshot;
import com.github.huchangjun118.containerconfigresolverjava.model.ConfigSourceVersion;
import com.github.huchangjun118.containerconfigresolverjava.model.ImageMetadata;
import com.github.huchangjun118.containerconfigresolverjava.model.RawContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.model.SourceLayer;
import com.github.huchangjun118.containerconfigresolverjava.resolver.ConfigResolver;
import com.github.huchangjun118.containerconfigresolverjava.validation.ConfigValidator;
import com.github.huchangjun118.containerconfigresolverjava.validation.HostConstraints;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发重载与读取测试：
 * 1) 读取到的快照必须整体自洽——字段 user 与 env.MARKER 必须来自同一个版本，
 *    绝不允许字段级混合；
 * 2) 慢读取不阻塞重载（重载持有锁的时间不受读取影响）；
 * 3) 失败版本与成功版本交替发布时，读取方永远只会看到已提交成功的版本。
 */
class ConcurrentReloadTest {

    private final ConfigResolver resolver = new ConfigResolver(
            new ConfigMerger(), new VariableInterpolator(),
            new ConfigValidator(new HostConstraints("linux",
                    java.util.Set.of("amd64"), java.util.Set.of(), false, false)));

    /** 每个版本的 user 与 env.MARKER 用同一标记构造，读取时校验二者一致。 */
    private ConfigSourceVersion version(int i) {
        return new ConfigSourceVersion("rev-" + i,
                new ImageMetadata("img", "linux", "amd64", List.of("/ep"), List.of("c")),
                List.of(new ConfigSourceVersion.LayeredSource(
                        SourceLayer.SERVICE, "svc-" + i,
                        RawContainerConfig.builder()
                                .user("u" + i)
                                .env(Map.of("MARKER", "m" + i))
                                .build())));
    }

    private ConfigSourceVersion invalidVersion(int i) {
        return new ConfigSourceVersion("bad-" + i,
                new ImageMetadata("img", "linux", "mips", List.of("/ep"), List.of("c")),
                List.of());
    }

    @Test
    void concurrentReadsAlwaysSeeOneSelfConsistentVersion() throws Exception {
        resolver.reload(version(0), Map.of());

        int readers = 16;
        int reloads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger mismatches = new AtomicInteger();
        AtomicLong reads = new AtomicLong();

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                while (!Thread.currentThread().isInterrupted()) {
                    resolver.current().ifPresent(snap -> {
                        reads.incrementAndGet();
                        String user = snap.resolved().getUser();
                        String marker = snap.resolved().getEnv().get("MARKER");
                        // user=uK 必须与 marker=mK 来自同一版本
                        if (user == null || marker == null
                                || !marker.equals("m" + user.substring(1))) {
                            mismatches.incrementAndGet();
                        }
                    });
                }
            });
        }

        AtomicBoolean reloadDone = new AtomicBoolean(false);
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 1; i <= reloads; i++) {
                    resolver.reload(version(i), Map.of());
                }
                reloadDone.set(true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        start.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!reloadDone.get() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(reloadDone.get(), "重载应在限时内完成");
        assertTrue(reads.get() > 0, "读取线程应实际读到过快照");
        assertTrue(mismatches.get() == 0,
                "发现字段级混合的快照数量: " + mismatches.get());
    }

    @Test
    void slowReaderDoesNotBlockReload() throws Exception {
        resolver.reload(version(0), Map.of());
        CommittedSnapshot held = resolver.current().orElseThrow();

        // 模拟一个拿到旧引用后慢慢使用的读取方
        AtomicBoolean slowReaderFinished = new AtomicBoolean(false);
        Thread slowReader = new Thread(() -> {
            try {
                // 持有旧快照引用并慢慢读 300ms；期间重载不应被阻塞
                long end = System.nanoTime() + 300_000_000L;
                while (System.nanoTime() < end) {
                    held.resolved().getUser();
                    held.resolved().getEnv().get("MARKER");
                    Thread.sleep(10);
                }
                slowReaderFinished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        slowReader.start();

        long t0 = System.nanoTime();
        for (int i = 1; i <= 20; i++) {
            resolver.reload(version(i), Map.of());
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // 纯内存合并 20 次应当极快完成；若被慢读取阻塞，会接近/超过慢读取耗时
        assertTrue(elapsedMs < 300,
                "重载被慢读取阻塞，耗时 " + elapsedMs + "ms");
        assertTrue(resolver.current().orElseThrow().revision().equals("rev-20"));

        slowReader.join(1000);
        assertTrue(slowReaderFinished.get());
        // 慢读取手里的仍是 rev-0 的自洽版本
        assertTrue("u0".equals(held.resolved().getUser()));
        assertTrue("m0".equals(held.resolved().getEnv().get("MARKER")));
    }

    @Test
    void failedReloadsNeverBecomeVisible() throws Exception {
        resolver.reload(version(0), Map.of());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> invalidRevisionsSeen = new ConcurrentLinkedQueue<>();
        AtomicInteger readers = new AtomicInteger(8);

        for (int r = 0; r < 8; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        resolver.current().ifPresent(s -> {
                            if (s.resolved().getImageArch() == null
                                    || !"amd64".equals(s.resolved().getImageArch())) {
                                invalidRevisionsSeen.add(s.revision());
                            }
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    readers.decrementAndGet();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                for (int i = 1; i <= 100; i++) {
                    resolver.reload(invalidVersion(i), Map.of());
                    if (i % 10 == 0) {
                        resolver.reload(version(i), Map.of());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertTrue(invalidRevisionsSeen.isEmpty(),
                "失败版本对外可见: " + invalidRevisionsSeen);
        assertTrue(resolver.lastFailure().isPresent()
                || resolver.current().isPresent());
    }
}
