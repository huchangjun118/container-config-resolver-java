package com.github.huchangjun118.containerconfigresolverjava.config.interpolate;

import com.github.huchangjun118.containerconfigresolverjava.config.model.ContainerConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 变量插值器。
 *
 * <p>语法：
 * <ul>
 *   <li>{@code ${field.path}}：引用本配置中的其他字段（如 {@code env.KEY}、{@code image}）；</li>
 *   <li>{@code ${env:NAME}}：引用外部取值（如环境变量）；</li>
 *   <li>{@code $$}：转义，输出字面量 {@code $}（如 {@code $${HOME}} 输出 {@code ${HOME}}）。</li>
 * </ul>
 *
 * <p>未定义引用、循环引用、类型错误、非法写法分别抛出
 * {@link UndefinedReferenceException}、{@link CircularReferenceException}、
 * {@link ReferenceTypeException}、{@link InvalidSyntaxException}，绝不静默替换或原样输出。
 *
 * <p>{@code $} 只能出现在两种合法形态中：{@code $$}（转义）与 {@code ${...}}（引用）。
 * 孤零零的 {@code $}（含落在字符串末尾）、{@code $x} 之类的跟随字符、未闭合的
 * {@code ${...}、空引用 {@code ${}}、空外部引用 {@code ${env:}} 都属于写法非法。
 */
public class Interpolator {

    private static final String EXTERNAL_PREFIX = "env:";

    public ContainerConfig interpolate(ContainerConfig config, Map<String, String> externalValues) {
        Map<String, String> external = externalValues == null ? Map.of() : externalValues;

        // 收集所有可插值的字符串字段。
        Map<String, String> scalars = new LinkedHashMap<>();
        put(scalars, "image", config.image());
        put(scalars, "platform", config.platform());
        put(scalars, "arch", config.arch());
        put(scalars, "workingDir", config.workingDir());
        put(scalars, "user", config.user());

        Map<String, String> env = config.env() == null ? Map.of() : config.env();
        Map<String, String> labels = config.labels() == null ? Map.of() : config.labels();

        // 解析标量字段（递归解析，带环检测）。
        Map<String, String> resolved = new LinkedHashMap<>();
        for (String key : scalars.keySet()) {
            resolved.put(key, resolveField(key, scalars, env, labels, external, new java.util.LinkedHashSet<>()));
        }

        // 解析 env / labels（值可能引用字段或外部取值）。
        Map<String, String> resolvedEnv = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String key = "env." + e.getKey();
            resolvedEnv.put(e.getKey(),
                    resolveField(key, scalars, env, labels, external, new java.util.LinkedHashSet<>()));
        }
        Map<String, String> resolvedLabels = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            String key = "labels." + e.getKey();
            resolvedLabels.put(e.getKey(),
                    resolveField(key, scalars, env, labels, external, new java.util.LinkedHashSet<>()));
        }

        // 解析 entrypoint / command 列表项。
        List<String> entrypoint = interpolateList(config.entrypoint(), scalars, env, labels, external);
        List<String> command = interpolateList(config.command(), scalars, env, labels, external);

        return ContainerConfig.builder()
                .image(resolved.get("image"))
                .platform(resolved.get("platform"))
                .arch(resolved.get("arch"))
                .workingDir(resolved.get("workingDir"))
                .user(resolved.get("user"))
                .env(resolvedEnv.isEmpty() ? (config.env() == null ? null : Map.of()) : resolvedEnv)
                .labels(resolvedLabels.isEmpty() ? (config.labels() == null ? null : Map.of()) : resolvedLabels)
                .entrypoint(entrypoint)
                .command(command)
                .ports(config.ports())
                .volumes(config.volumes())
                .privileged(config.privileged())
                .readOnlyRootFilesystem(config.readOnlyRootFilesystem())
                .build();
    }

    private List<String> interpolateList(List<String> values, Map<String, String> scalars,
                                         Map<String, String> env, Map<String, String> labels,
                                         Map<String, String> external) {
        if (values == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String item : values) {
            // 列表项引用其他字段时，不允许引用列表字段本身；其余按标量解析。
            out.add(resolveTemplate(item, scalars, env, labels, external, new java.util.LinkedHashSet<>()));
        }
        return out;
    }

    private void put(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** 解析一个字段 key 的最终字符串值（递归处理引用）。 */
    private String resolveField(String key, Map<String, String> scalars,
                                 Map<String, String> env, Map<String, String> labels,
                                 Map<String, String> external, Set<String> stack) {
        if (stack.contains(key)) {
            throw new CircularReferenceException(
                    "circular reference detected: " + String.join(" -> ", stack) + " -> " + key);
        }

        String template;
        if (scalars.containsKey(key)) {
            template = scalars.get(key);
        } else if (key.startsWith("env.")) {
            String name = key.substring("env.".length());
            template = env.get(name);
        } else if (key.startsWith("labels.")) {
            String name = key.substring("labels.".length());
            template = labels.get(name);
        } else {
            throw new UndefinedReferenceException("undefined field reference: " + key);
        }
        if (template == null) {
            throw new UndefinedReferenceException("undefined field reference: " + key);
        }

        Set<String> next = new java.util.LinkedHashSet<>(stack);
        next.add(key);
        return resolveTemplate(template, scalars, env, labels, external, next);
    }

    /** 解析模板字符串：替换 ${...} 引用，处理 $$ 转义。 */
    private String resolveTemplate(String template, Map<String, String> scalars,
                                   Map<String, String> env, Map<String, String> labels,
                                   Map<String, String> external, Set<String> stack) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '$') {
                // $ 必须构成 $$ 转义或 ${...} 引用，否则一律按非法写法报错，
                // 绝不原样带出，也不静默替换成空串。
                if (i + 1 >= n) {
                    throw new InvalidSyntaxException(
                            "invalid interpolation syntax: dangling '$' at end of: " + template, template);
                }
                char next = template.charAt(i + 1);
                if (next == '$') {
                    // 转义：$$ -> $
                    out.append('$');
                    i += 2;
                    continue;
                }
                if (next == '{') {
                    int close = template.indexOf('}', i + 2);
                    if (close < 0) {
                        throw new InvalidSyntaxException(
                                "invalid interpolation syntax: unterminated '${' in: " + template, template);
                    }
                    String spec = template.substring(i + 2, close);
                    out.append(resolveReference(spec, scalars, env, labels, external, stack));
                    i = close + 1;
                    continue;
                }
                throw new InvalidSyntaxException(
                        "invalid interpolation syntax: '$' must be escaped as '$$' or start a '${...}' "
                                + "reference, got '$" + next + "' in: " + template,
                        template);
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private String resolveReference(String spec, Map<String, String> scalars,
                                    Map<String, String> env, Map<String, String> labels,
                                    Map<String, String> external, Set<String> stack) {
        if (spec.isEmpty()) {
            throw new InvalidSyntaxException(
                    "invalid interpolation syntax: empty reference '${}'", "${}");
        }

        if (spec.startsWith(EXTERNAL_PREFIX)) {
            String name = spec.substring(EXTERNAL_PREFIX.length());
            if (name.isEmpty()) {
                throw new InvalidSyntaxException(
                        "invalid interpolation syntax: empty external reference '${env:}'", "${env:}");
            }
            String value = external.get(name);
            if (value == null) {
                throw new UndefinedReferenceException("undefined external reference: ${env:" + name + "}");
            }
            return value;
        }

        // 字段引用：不允许引用列表字段。
        if (spec.equals("entrypoint") || spec.equals("command") || spec.equals("ports") || spec.equals("volumes")) {
            throw new ReferenceTypeException(
                    "cannot interpolate list field in scalar context: " + spec);
        }
        return resolveField(spec, scalars, env, labels, external, stack);
    }
}
