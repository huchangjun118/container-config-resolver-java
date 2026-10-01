package com.github.huchangjun118.containerconfigresolverjava.interpolation;

import com.github.huchangjun118.containerconfigresolverjava.model.ResolvedContainerConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 变量插值器：对合并后的字符串字段做引用展开。
 *
 * <ul>
 *   <li>{@code ${NAME}} / {@code $NAME}：外部注入变量（reload 时显式传入的 externals）；</li>
 *   <li>{@code ${env.KEY}}：引用最终 env 中的其他条目；</li>
 *   <li>{@code ${field.image}} / {@code ${field.platform}} /
 *       {@code ${field.workingDir}} / {@code ${field.user}}：引用其他字段的<em>最终值</em>
 *       （字段自身也会先完成插值）；</li>
 *   <li>{@code $$} 为字面量 {@code $}；{@code $${x}} 为字面量 {@code ${x}}，不展开。</li>
 * </ul>
 *
 * <p>env 条目与可引用字段被建模为一张惰性求值图，节点记忆化、共享引用链：
 * 字段引用 env、env 引用字段、env 互相引用都会拿到对方展开后的最终值；
 * 任意方向的环都会被识别。未定义引用、循环引用、非法表达式分别抛出三类
 * 可区分异常，绝不静默替换为空值或原样输出。</p>
 */
public final class VariableInterpolator {

    private static final Set<String> FIELD_REFS = Set.of("image", "platform", "workingDir", "user");

    private Map<String, String> externals;
    private Map<String, String> rawEnv;

    private final Map<String, String> envCache = new HashMap<>();
    private final Map<String, String> fieldCache = new HashMap<>();
    /** 当前求值链上的节点，形如 env:KEY / field:NAME。 */
    private final Deque<String> visiting = new ArrayDeque<>();

    public VariableInterpolator() {
    }

    public ResolvedContainerConfig interpolate(ResolvedContainerConfig merged, Map<String, String> externals) {
        this.externals = externals == null ? Map.of() : externals;
        this.rawEnv = merged.getEnv();
        this.envCache.clear();
        this.fieldCache.clear();
        this.visiting.clear();
        this.rawImageValue = merged.getImage();
        this.rawPlatformValue = merged.getPlatform();
        this.rawWorkingDirValue = merged.getWorkingDir();
        this.rawUserValue = merged.getUser();

        // 按确定性顺序触发求值，保证相同输入的报错位置稳定
        Map<String, String> resolvedEnv = new LinkedHashMap<>();
        for (String key : rawEnv.keySet()) {
            resolvedEnv.put(key, resolveEnv(key));
        }

        String image = resolveField("image", merged.getImage());
        String platform = resolveField("platform", merged.getPlatform());
        String workingDir = resolveField("workingDir", merged.getWorkingDir());
        String user = resolveField("user", merged.getUser());

        List<String> entrypoint = evalList(merged.getEntrypoint(), "entrypoint");
        List<String> command = evalList(merged.getCommand(), "command");
        Map<String, String> labels = evalMap(merged.getLabels(), "labels");

        return merged.toBuilder()
                .image(image)
                .platform(platform)
                .workingDir(workingDir)
                .user(user)
                .env(resolvedEnv)
                .labels(labels)
                .entrypoint(entrypoint)
                .command(command)
                .build();
    }

    // ------------------------------------------------------------------
    // 惰性求值图：env:* 与 field:* 两类节点
    // ------------------------------------------------------------------

    private String resolveEnv(String key) {
        if (envCache.containsKey(key)) {
            return envCache.get(key);
        }
        String node = "env:" + key;
        enterNode(node, "env." + key);
        try {
            if (!rawEnv.containsKey(key)) {
                throw new UndefinedReferenceException(
                        "未定义的变量引用：env." + key, "env." + key, "env." + key);
            }
            String raw = rawEnv.get(key);
            String value = raw == null ? null : eval(raw, "env." + key);
            envCache.put(key, value);
            return value;
        } finally {
            exitNode(node);
        }
    }

    private String resolveField(String name, String raw) {
        if (fieldCache.containsKey(name)) {
            return fieldCache.get(name);
        }
        String node = "field:" + name;
        enterNode(node, "field." + name);
        try {
            String value = raw == null ? null : eval(raw, "field." + name);
            fieldCache.put(name, value);
            return value;
        } finally {
            exitNode(node);
        }
    }

    private void enterNode(String node, String displayPath) {
        if (visiting.contains(node)) {
            List<String> chain = new ArrayList<>(visiting.size() + 1);
            visiting.forEach(n -> chain.add(display(n)));
            chain.add(displayPath);
            throw new CircularReferenceException("检测到变量循环引用", displayPath, chain);
        }
        visiting.addLast(node);
    }

    private void exitNode(String node) {
        if (node.equals(visiting.peekLast())) {
            visiting.removeLast();
        }
    }

    private static String display(String node) {
        return node.startsWith("env:") ? "env." + node.substring(4)
                : node.startsWith("field:") ? "field." + node.substring(6)
                : node;
    }

    // ------------------------------------------------------------------
    // 字符串扫描
    // ------------------------------------------------------------------

    private List<String> evalList(List<String> values, String path) {
        List<String> out = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            out.add(eval(values.get(i), path + "[" + i + "]"));
        }
        return out;
    }

    private Map<String, String> evalMap(Map<String, String> values, String path) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            out.put(e.getKey(), eval(e.getValue(), path + "." + e.getKey()));
        }
        return out;
    }

    /**
     * 解析单个字符串：
     * $$ -> 字面 $；${...} -> 大括号引用；$X -> 裸变量引用；其余原样。
     */
    private String eval(String raw, String path) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char ch = raw.charAt(i);
            if (ch != '$') {
                out.append(ch);
                i++;
                continue;
            }
            if (i + 1 >= n) {
                throw new MalformedReferenceException(
                        "非法的插值表达式：结尾孤立的 '$'", path, raw);
            }
            char next = raw.charAt(i + 1);
            if (next == '$') {
                out.append('$');
                i += 2;
            } else if (next == '{') {
                int close = raw.indexOf('}', i + 2);
                if (close < 0) {
                    throw new MalformedReferenceException(
                            "非法的插值表达式：缺少 '}'", path, raw);
                }
                String ref = raw.substring(i + 2, close).trim();
                out.append(resolveRef(ref, path, raw));
                i = close + 1;
            } else if (isIdentifierStart(next)) {
                int j = i + 1;
                while (j < n && isIdentifierPart(raw.charAt(j))) {
                    j++;
                }
                String name = raw.substring(i + 1, j);
                out.append(resolveRef(name, path, raw));
                i = j;
            } else {
                throw new MalformedReferenceException(
                        "非法的插值表达式：'$' 后只能是 '$'、'{' 或变量名", path, raw);
            }
        }
        return out.toString();
    }

    private String resolveRef(String ref, String path, String rawExpression) {
        if (ref.isEmpty()) {
            throw new MalformedReferenceException(
                    "非法的插值表达式：空引用 '${}'", path, rawExpression);
        }
        if (ref.startsWith("env.")) {
            String key = ref.substring(4);
            if (key.isEmpty()) {
                throw new MalformedReferenceException(
                        "非法的插值表达式：env. 后缺少变量名", path, rawExpression);
            }
            String value = resolveEnv(key);
            if (value == null) {
                throw new UndefinedReferenceException(
                        "变量 env." + key + " 的值为 null", path, ref);
            }
            return value;
        }
        if (ref.startsWith("field.")) {
            String name = ref.substring(6);
            if (!FIELD_REFS.contains(name)) {
                throw new UndefinedReferenceException(
                        "不支持的字段引用：field." + name
                                + "（允许 image/platform/workingDir/user）",
                        path, ref);
            }
            String raw = switch (name) {
                case "image" -> rawImage();
                case "platform" -> rawPlatform();
                case "workingDir" -> rawWorkingDir();
                case "user" -> rawUser();
                default -> null;
            };
            if (raw == null) {
                throw new UndefinedReferenceException(
                        "字段 field." + name + " 未定义", path, ref);
            }
            String value = resolveField(name, raw);
            if (value == null) {
                throw new UndefinedReferenceException(
                        "字段 field." + name + " 未定义", path, ref);
            }
            return value;
        }
        // 普通变量：只允许引用显式注入的外部取值，
        // 不回退进程环境变量，以保证跨进程结果确定。
        if (!externals.containsKey(ref)) {
            throw new UndefinedReferenceException(
                    "未定义的变量引用：" + ref, path, ref);
        }
        String value = externals.get(ref);
        if (value == null) {
            throw new UndefinedReferenceException(
                    "外部变量 " + ref + " 的值为 null", path, ref);
        }
        return value;
    }

    // 字段原始值在求值图构建时即固定，避免与最终值混淆
    private String rawImageValue;
    private String rawPlatformValue;
    private String rawWorkingDirValue;
    private String rawUserValue;

    private String rawImage() { return rawImageValue; }
    private String rawPlatform() { return rawPlatformValue; }
    private String rawWorkingDir() { return rawWorkingDirValue; }
    private String rawUser() { return rawUserValue; }

    private static boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_';
    }

    private static boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }
}
