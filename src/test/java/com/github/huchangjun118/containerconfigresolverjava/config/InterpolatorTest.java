package com.github.huchangjun118.containerconfigresolverjava.config;

import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.CircularReferenceException;
import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.InterpolationException;
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

    @Test
    void danglingDollarAtEndOfValue_throwsInvalidSyntax() {
        ContainerConfig c = ContainerConfig.builder()
                .image("registry/app$")
                .build();
        InvalidSyntaxException ex = assertThrows(InvalidSyntaxException.class,
                () -> interpolator.interpolate(c, Map.of()));
        assertTrue(ex.getMessage().contains("dangling"));
    }

    @Test
    void dollarFollowedByPlainChar_throwsInvalidSyntax_notPassedThrough() {
        ContainerConfig c = ContainerConfig.builder()
                .env(Map.of("HOME_REF", "$HOME"))
                .build();
        // $H 既不是合法引用也不是合法转义：必须报错，不得原样输出
        assertThrows(InvalidSyntaxException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void unterminatedReference_throwsInvalidSyntax() {
        ContainerConfig c = ContainerConfig.builder()
                .image("${env.REGISTRY")
                .build();
        InvalidSyntaxException ex = assertThrows(InvalidSyntaxException.class,
                () -> interpolator.interpolate(c, Map.of()));
        assertTrue(ex.getMessage().contains("unterminated"));
    }

    @Test
    void emptyReference_throwsInvalidSyntax() {
        ContainerConfig c = ContainerConfig.builder()
                .image("registry/${}/app")
                .build();
        assertThrows(InvalidSyntaxException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void invalidSyntaxInListItem_failsEntireEvaluation() {
        ContainerConfig c = ContainerConfig.builder()
                .image("img:1")
                .command(List.of("--port", "8080$"))
                .build();
        assertThrows(InvalidSyntaxException.class, () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void errorCategories_areDistinguishable() {
        // 四类错误互不混淆，且都属于插值异常
        ContainerConfig syntaxErr = ContainerConfig.builder().image("app$").build();
        ContainerConfig undefinedErr = ContainerConfig.builder().image("${env.X}").build();
        ContainerConfig circularErr = ContainerConfig.builder()
                .env(Map.of("A", "${env.A}")).build();
        ContainerConfig typeErr = ContainerConfig.builder()
                .image("${entrypoint}").entrypoint(List.of("/app")).build();

        Exception syntax = assertThrows(InvalidSyntaxException.class,
                () -> interpolator.interpolate(syntaxErr, Map.of()));
        Exception undefined = assertThrows(UndefinedReferenceException.class,
                () -> interpolator.interpolate(undefinedErr, Map.of()));
        Exception circular = assertThrows(CircularReferenceException.class,
                () -> interpolator.interpolate(circularErr, Map.of()));
        Exception type = assertThrows(ReferenceTypeException.class,
                () -> interpolator.interpolate(typeErr, Map.of()));

        // 都可按插值异常统一捕获，但子类型互不相同
        assertTrue(syntax instanceof InterpolationException);
        assertTrue(undefined instanceof InterpolationException);
        assertTrue(circular instanceof InterpolationException);
        assertTrue(type instanceof InterpolationException);
        assertTrue(!(syntax instanceof UndefinedReferenceException));
        assertTrue(!(undefined instanceof InvalidSyntaxException));
        assertTrue(!(circular instanceof InvalidSyntaxException));
        assertTrue(!(type instanceof InvalidSyntaxException));
    }
}
