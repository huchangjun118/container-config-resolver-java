package com.github.huchangjun118.containerconfigresolverjava.tests;

import com.github.huchangjun118.containerconfigresolverjava.interpolation.CircularReferenceException;
import com.github.huchangjun118.containerconfigresolverjava.interpolation.MalformedReferenceException;
import com.github.huchangjun118.containerconfigresolverjava.interpolation.UndefinedReferenceException;
import com.github.huchangjun118.containerconfigresolverjava.interpolation.VariableInterpolator;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolvedContainerConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableInterpolatorTest {

    private final VariableInterpolator interpolator = new VariableInterpolator();

    private ResolvedContainerConfig config(Map<String, String> env) {
        return ResolvedContainerConfig.builder()
                .image("registry.example.com/app:1")
                .platform("linux/amd64")
                .workingDir("/app")
                .env(env)
                .build();
    }

    @Test
    void expandsExternalAndEnvAndFieldReferences() {
        ResolvedContainerConfig c = config(Map.of(
                "GREETING", "hello ${NAME}",
                "DESC", "img=${field.image} os=${field.platform}"));
        ResolvedContainerConfig out = interpolator.interpolate(c, Map.of("NAME", "world"));

        assertEquals("hello world", out.getEnv().get("GREETING"));
        assertEquals("img=registry.example.com/app:1 os=linux/amd64", out.getEnv().get("DESC"));
    }

    @Test
    void expandsBareDollarReference() {
        ResolvedContainerConfig out = interpolator.interpolate(
                config(Map.of("V", "x-$NAME-y")), Map.of("NAME", "z"));
        assertEquals("x-z-y", out.getEnv().get("V"));
    }

    @Test
    void dollarDollarIsEscapedToLiteralDollar() {
        ResolvedContainerConfig out = interpolator.interpolate(
                config(Map.of("V", "price: $$5")), Map.of());
        assertEquals("price: $5", out.getEnv().get("V"));
    }

    @Test
    void dollarDollarBraceIsLiteralExpressionNotExpanded() {
        ResolvedContainerConfig out = interpolator.interpolate(
                config(Map.of("V", "raw $${UNDEFINED} end")), Map.of());
        assertEquals("raw ${UNDEFINED} end", out.getEnv().get("V"));
    }

    @Test
    void undefinedExternalReferenceIsRejectedNotEmptied() {
        UndefinedReferenceException e = assertThrows(UndefinedReferenceException.class,
                () -> interpolator.interpolate(config(Map.of("V", "${MISSING}")), Map.of()));
        assertEquals("MISSING", e.reference());
        assertEquals("env.V", e.path());
    }

    @Test
    void undefinedEnvKeyReferenceIsRejected() {
        UndefinedReferenceException e = assertThrows(UndefinedReferenceException.class,
                () -> interpolator.interpolate(config(Map.of("V", "${env.NOPE}")), Map.of()));
        assertEquals("env.NOPE", e.reference());
    }

    @Test
    void unsupportedFieldReferenceIsRejected() {
        UndefinedReferenceException e = assertThrows(UndefinedReferenceException.class,
                () -> interpolator.interpolate(config(Map.of("V", "${field.privileged}")), Map.of()));
        assertTrue(e.getMessage().contains("不支持的字段引用"));
    }

    @Test
    void directSelfReferenceIsCircular() {
        CircularReferenceException e = assertThrows(CircularReferenceException.class,
                () -> interpolator.interpolate(config(Map.of("A", "${env.A}")), Map.of()));
        assertTrue(e.chain().contains("env.A"));
        assertTrue(e.getMessage().contains("env.A"));
    }

    @Test
    void indirectCycleIsDetected() {
        CircularReferenceException e = assertThrows(CircularReferenceException.class,
                () -> interpolator.interpolate(
                        config(Map.of("A", "${env.B}", "B", "${env.A}")), Map.of()));
        assertTrue(e.getMessage().contains("cycle"));
    }

    @Test
    void unclosedBraceIsMalformed() {
        MalformedReferenceException e = assertThrows(MalformedReferenceException.class,
                () -> interpolator.interpolate(config(Map.of("V", "value ${X")), Map.of("X", "1")));
        assertEquals("value ${X", e.rawExpression());
    }

    @Test
    void emptyBracesAreMalformed() {
        assertThrows(MalformedReferenceException.class,
                () -> interpolator.interpolate(config(Map.of("V", "${}")), Map.of()));
    }

    @Test
    void trailingDollarIsMalformed() {
        assertThrows(MalformedReferenceException.class,
                () -> interpolator.interpolate(config(Map.of("V", "abc$")), Map.of()));
    }

    @Test
    void otherFieldReferenceToUnsetFieldIsUndefined() {
        ResolvedContainerConfig noWd = ResolvedContainerConfig.builder()
                .env(Map.of("V", "${field.workingDir}")).build();
        assertThrows(UndefinedReferenceException.class,
                () -> interpolator.interpolate(noWd, Map.of()));
    }

    @Test
    void fieldReferenceResolvesReferencedFieldFinalValue() {
        // workingDir 自身引用外部变量，user 再引用 workingDir 的最终值
        ResolvedContainerConfig c = ResolvedContainerConfig.builder()
                .workingDir("/srv/${ENV_NAME}")
                .env(Map.of("PWD_DESC", "workdir=${field.workingDir}"))
                .build();
        ResolvedContainerConfig out = interpolator.interpolate(c, Map.of("ENV_NAME", "prod"));
        assertEquals("/srv/prod", out.getWorkingDir());
        assertEquals("workdir=/srv/prod", out.getEnv().get("PWD_DESC"));
    }

    @Test
    void cycleBetweenFieldAndEnvIsDetected() {
        ResolvedContainerConfig c = ResolvedContainerConfig.builder()
                .image("${env.A}")
                .env(Map.of("A", "${field.image}"))
                .build();
        assertThrows(CircularReferenceException.class,
                () -> interpolator.interpolate(c, Map.of()));
    }

    @Test
    void externalValuesAreNotExpandedAgain() {
        // 外部注入值即使长得像表达式，也按字面量注入，不做二次展开
        ResolvedContainerConfig out = interpolator.interpolate(
                config(Map.of("V", "${X}")), Map.of("X", "${Y}", "Y", "y"));
        assertEquals("${Y}", out.getEnv().get("V"));
    }
}
