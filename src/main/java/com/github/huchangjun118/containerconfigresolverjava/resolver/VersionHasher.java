package com.github.huchangjun118.containerconfigresolverjava.resolver;

import com.github.huchangjun118.containerconfigresolverjava.model.Mount;
import com.github.huchangjun118.containerconfigresolverjava.model.PortBinding;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolvedContainerConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * 对最终运行定义做确定性指纹：相同字段值 => 相同 versionId（SHA-256），
 * 与求值时间、进程、JVM 无关。采用固定字段顺序、固定转义的行式规范序列化，
 * 不包含来源命名/追溯等元数据，从而让"定义等价"的版本得到相同指纹。
 */
public final class VersionHasher {

    private VersionHasher() {
    }

    public static String hash(ResolvedContainerConfig c) {
        StringBuilder sb = new StringBuilder();
        line(sb, "image", c.getImage());
        line(sb, "platform", c.getPlatform());
        line(sb, "imageOs", c.getImageOs());
        line(sb, "imageArch", c.getImageArch());
        line(sb, "entrypoint", list(c.getEntrypoint()));
        line(sb, "command", list(c.getCommand()));
        line(sb, "workingDir", c.getWorkingDir());
        line(sb, "user", c.getUser());
        line(sb, "env", map(c.getEnv()));
        line(sb, "labels", map(c.getLabels()));
        line(sb, "ports", ports(c.getPorts()));
        line(sb, "mounts", mounts(c.getMounts()));
        line(sb, "capabilities", list(c.getCapabilities()));
        line(sb, "privileged", c.isPrivileged() ? "1" : "0");
        line(sb, "readOnlyRootfs", c.isReadOnlyRootfs() ? "1" : "0");
        return sha256Hex(sb.toString());
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

    private static String list(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(values.get(i)));
        }
        return sb.toString();
    }

    private static String map(Map<String, String> m) {
        // getEnv/getLabels 已是 TreeMap（确定性键序）
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return sb.toString();
    }

    private static String ports(List<PortBinding> ports) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ports.size(); i++) {
            if (i > 0) sb.append(';');
            PortBinding p = ports.get(i);
            sb.append(p.hostIp()).append(':').append(p.hostPort())
              .append("->").append(p.containerPort()).append('/').append(p.protocol());
        }
        return sb.toString();
    }

    private static String mounts(List<Mount> mounts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mounts.size(); i++) {
            if (i > 0) sb.append(';');
            Mount m = mounts.get(i);
            sb.append(m.type()).append(':')
              .append(m.source() == null ? "" : m.source())
              .append("->").append(m.target())
              .append(m.readOnly() ? ":ro" : ":rw");
        }
        return sb.toString();
    }

    /** 仅对逗号/冒号/分号等分隔符和引号、换行做转义，保证可逆且跨平台一致。 */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case ',' -> sb.append("\\,");
                case ';' -> sb.append(";");
                case ':' -> sb.append("\\:");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    static String sha256Hex(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
