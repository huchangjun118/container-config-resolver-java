package com.github.huchangjun118.containerconfigresolverjava.config;

import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.CircularReferenceException;
import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.Interpolator;
import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.InvalidSyntaxException;
import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.ReferenceTypeException;
import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.UndefinedReferenceException;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ContainerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpolatorTest {

    private final Interpolator interpolator = new Interpolator();

    @Test
    void resolvesFieldReferencesAndExternalValues() {
        ContainerConfig c = ContainerConfig.builder()
                .image("registry/${env:REGISTRY}/server:${env:TAG}")
                .env(Map.of("BASE", "/opt/app", "HOME_REF", "${env.BASE}/home"))
                .command(List.of("--config", "${env:CONFIG_PATH}"))
                .build();

        ContainerConfig out = interpolator.interpolate(c, Map.of(
                "REGISTRY", "example.com",
                "TAG", "1.0.0",
                "CONFIG_PATH", "/etc/app.conf"));

        assertEquals("registry/example.com/server:1.0.0", out.image());
        assertEquals("/opt/app/home", out.env().get("HOME_REF"));
        assertEquals("/etc/app.conf", out.command().get(1));
    }

    @Test
    void escapeDollarSign_outputsLiteralReference() {
        ContainerConfig c = ContainerConfig.builder()
                .image("$${env:REGISTRY}")
                .env(Map.of("LITERAL", "$$HOME"))
                .build();

        ContainerConfig out = interpolator.interpolate(c, Map.of("REGISTRY", "example.com"));
        assertEquals("${env:REGISTRY}", out.image());
        assertEquals("$HOME", out.env().get("LITERAL"));
    }

    @Test
    void undefinedFieldReference_throwsDistinguishableException() {
        ContainerConfig c = ContainerConfig.builder()
                .image("${env.MISSING}")
                .build();
        UndefinedReferenceException ex = assertThrows(UndefinedReferenceException.class,
                () -> interpolator.interpolate(c, Map.of()));
        assertTrue(ex.getMessage().contains("env.MISSING"));
    }

    @Test
    void undefinedExternalReference_throwsDistinguishableException() {
        ContainerConfig c = ContainerConfig.builder()
                .image("${env:MISSING_VAR}")
                .build();
        UndefinedReferenceException ex = assertThrows(UndefinedReferenceException.class,
                () -> interpolator.interpolate(c, Map.of()));
        assertTrue(ex.getMessage().contains("MISSING_VAR"));
    }

    @Test
    void circularReference_throwsDistinguishableException() {
        ContainerConfig c = ContainerConfig.builder()
                .env(Map.of("A", "${env.B}", "B", "${env.A}"))
                .build();
        CircularReferenceException ex = assertThrows(CircularReferenceException.class,
                () -> interpolator.interpolate(c, Map.of()));
        assertTrue(ex.getMessage().contains("circular"));
    }

    @Test
    void selfReference_isCircular() {
        ContainerConfig c = ContainerConfig.builder()
                .env(Map.of("A", "${env.A}"))
                .build();
        assertThrows(CircularReferenceException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void referencingListFieldInScalarContext_throwsTypeException() {
        ContainerConfig c = ContainerConfig.builder()
                .image("${entrypoint}")
                .entrypoint(List.of("/app/server"))
                .build();
        assertThrows(ReferenceTypeException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void unresolvedReferenceFailsEntireEvaluation_notSilentlyReplaced() {
        ContainerConfig c = ContainerConfig.builder()
                .image("ok:${env:TAG}")
                .env(Map.of("BROKEN", "${env.NO_SUCH_KEY}"))
                .build();
        // 即使 image 可解析，整体仍失败
        assertThrows(UndefinedReferenceException.class,
                () -> interpolator.interpolate(c, Map.of("TAG", "1")));
    }

    // ---- 非法 $ 写法：必须报错，绝不原样带出，也不替换成空串 ----

    @Test
    void danglingDollarAtEnd_throwsInvalidSyntax() {
        ContainerConfig c = ContainerConfig.builder().image("abc$").build();
        InvalidSyntaxException ex = assertThrows(InvalidSyntaxException.class,
                () -> interpolator.interpolate(c, Map.of()));
        assertTrue(ex.getMessage().contains("dangling"));
        assertEquals("abc$", ex.offendingText());
    }

    @Test
    void loneDollarOnly_throwsInvalidSyntax() {
        ContainerConfig c = ContainerConfig.builder().image("$").build();
        assertThrows(InvalidSyntaxException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void dollarFollowedByLetter_throwsInvalidSyntax_notPassedThrough() {
        ContainerConfig c = ContainerConfig.builder().image("price is $5 today").build();
        InvalidSyntaxException ex = assertThrows(InvalidSyntaxException.class,
                () -> interpolator.interpolate(c, Map.of()));
        assertTrue(ex.getMessage().contains("$5"));
    }

    @Test
    void oddNumberOfDollarsAtEnd_throwsInvalidSyntax() {
        // $$$ -> 第一对 $$ 转义成一个 $，最后一个 $ 悬空落在结尾 -> 非法
        ContainerConfig c = ContainerConfig.builder().image("$$$").build();
        InvalidSyntaxException ex = assertThrows(InvalidSyntaxException.class,
                () -> interpolator.interpolate(c, Map.of()));
        assertTrue(ex.getMessage().contains("dangling"));
    }

    @Test
    void unterminatedBraceReference_throwsInvalidSyntax() {
        ContainerConfig c = ContainerConfig.builder().image("${env:TAG").build();
        assertThrows(InvalidSyntaxException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void emptyReference_throwsInvalidSyntax() {
        ContainerConfig c = ContainerConfig.builder().image("${}").build();
        assertThrows(InvalidSyntaxException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void emptyExternalReference_throwsInvalidSyntax_notUndefined() {
        ContainerConfig c = ContainerConfig.builder().image("${env:}").build();
        assertThrows(InvalidSyntaxException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void danglingDollarInListItem_throwsInvalidSyntax() {
        ContainerConfig c = ContainerConfig.builder()
                .entrypoint(List.of("/app/server", "--flag=$"))
                .build();
        assertThrows(InvalidSyntaxException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void invalidSyntaxIsDistinguishableFromOtherInterpolationFailures() {
        // 写法非法 vs 取值不存在
        assertThrows(InvalidSyntaxException.class,
                () -> interpolator.interpolate(ContainerConfig.builder().image("$").build(), Map.of()));
        assertThrows(UndefinedReferenceException.class,
                () -> interpolator.interpolate(
                        ContainerConfig.builder().image("${env:NOPE}").build(), Map.of()));

        // 写法非法 vs 引用成环
        assertThrows(CircularReferenceException.class,
                () -> interpolator.interpolate(
                        ContainerConfig.builder().env(Map.of("A", "${env.A}")).build(), Map.of()));

        // 写法非法 vs 引用类型不对
        assertThrows(ReferenceTypeException.class,
                () -> interpolator.interpolate(ContainerConfig.builder()
                        .image("${entrypoint}")
                        .entrypoint(List.of("/app/server"))
                        .build(), Map.of()));
    }

    @Test
    void legalEscapeStillResolves() {
        // $$ 转义与悬空 $ 的边界：结尾两个 $ 合法、一个 $ 非法
        assertEquals("$", interpolator.interpolate(
                ContainerConfig.builder().image("$$").build(), Map.of()).image());
        assertEquals("${HOME}", interpolator.interpolate(
                ContainerConfig.builder().image("$${HOME}").build(), Map.of("HOME", "/root")).image());
        assertEquals("a$b", interpolator.interpolate(
                ContainerConfig.builder().image("a$$b").build(), Map.of()).image());
    }
}
