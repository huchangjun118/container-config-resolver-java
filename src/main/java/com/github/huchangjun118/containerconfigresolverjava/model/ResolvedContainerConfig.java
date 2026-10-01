package com.github.huchangjun118.containerconfigresolverjava.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次求值产出的最终运行定义。不可变；集合内容做了确定性排序，
 * 保证相同输入跨进程得到逐字段一致的结果。每个字段路径都可在
 * {@link #origins} 中追溯到来源（含数组元素与 map 条目）。
 */
public final class ResolvedContainerConfig {

    private final String image;
    private final String platform;
    private final String imageOs;
    private final String imageArch;
    private final List<String> entrypoint;
    private final List<String> command;
    private final String workingDir;
    private final String user;
    private final Map<String, String> env;
    private final Map<String, String> labels;
    private final List<PortBinding> ports;
    private final List<Mount> mounts;
    private final List<String> capabilities;
    private final boolean privileged;
    private final boolean readOnlyRootfs;
    private final Map<String, FieldOrigin> origins;

    public ResolvedContainerConfig(
            String image, String platform, String imageOs, String imageArch,
            List<String> entrypoint, List<String> command, String workingDir, String user,
            Map<String, String> env, Map<String, String> labels,
            List<PortBinding> ports, List<Mount> mounts, List<String> capabilities,
            boolean privileged, boolean readOnlyRootfs,
            Map<String, FieldOrigin> origins) {
        this.image = image;
        this.platform = platform;
        this.imageOs = imageOs;
        this.imageArch = imageArch;
        this.entrypoint = entrypoint == null ? List.of() : List.copyOf(entrypoint);
        this.command = command == null ? List.of() : List.copyOf(command);
        this.workingDir = workingDir;
        this.user = user;
        this.env = env == null ? Map.of() : Collections.sorted(env);
        this.labels = labels == null ? Map.of() : Collections.sorted(labels);
        this.ports = ports == null ? List.of() : List.copyOf(ports);
        this.mounts = mounts == null ? List.of() : List.copyOf(mounts);
        this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        this.privileged = privileged;
        this.readOnlyRootfs = readOnlyRootfs;
        this.origins = origins == null ? Map.of() : Map.copyOf(origins);
    }

    public String getImage() { return image; }
    public String getPlatform() { return platform; }
    public String getImageOs() { return imageOs; }
    public String getImageArch() { return imageArch; }
    public List<String> getEntrypoint() { return entrypoint; }
    public List<String> getCommand() { return command; }
    public String getWorkingDir() { return workingDir; }
    public String getUser() { return user; }
    public Map<String, String> getEnv() { return env; }
    public Map<String, String> getLabels() { return labels; }
    public List<PortBinding> getPorts() { return ports; }
    public List<Mount> getMounts() { return mounts; }
    public List<String> getCapabilities() { return capabilities; }
    public boolean isPrivileged() { return privileged; }
    public boolean isReadOnlyRootfs() { return readOnlyRootfs; }
    public Map<String, FieldOrigin> getOrigins() { return origins; }

    public FieldOrigin originOf(String path) {
        return origins.get(path);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.image = image;
        b.platform = platform;
        b.imageOs = imageOs;
        b.imageArch = imageArch;
        b.entrypoint = new ArrayList<>(entrypoint);
        b.command = new ArrayList<>(command);
        b.workingDir = workingDir;
        b.user = user;
        b.env = new LinkedHashMap<>(env);
        b.labels = new LinkedHashMap<>(labels);
        b.ports = new ArrayList<>(ports);
        b.mounts = new ArrayList<>(mounts);
        b.capabilities = new ArrayList<>(capabilities);
        b.privileged = privileged;
        b.readOnlyRootfs = readOnlyRootfs;
        b.origins = new LinkedHashMap<>(origins);
        return b;
    }

    public static final class Builder {
        private String image;
        private String platform;
        private String imageOs;
        private String imageArch;
        private List<String> entrypoint = new ArrayList<>();
        private List<String> command = new ArrayList<>();
        private String workingDir;
        private String user;
        private Map<String, String> env = new LinkedHashMap<>();
        private Map<String, String> labels = new LinkedHashMap<>();
        private List<PortBinding> ports = new ArrayList<>();
        private List<Mount> mounts = new ArrayList<>();
        private List<String> capabilities = new ArrayList<>();
        private boolean privileged;
        private boolean readOnlyRootfs;
        private Map<String, FieldOrigin> origins = new LinkedHashMap<>();

        public Builder image(String v) { this.image = v; return this; }
        public Builder platform(String v) { this.platform = v; return this; }
        public Builder imageOs(String v) { this.imageOs = v; return this; }
        public Builder imageArch(String v) { this.imageArch = v; return this; }
        public Builder entrypoint(List<String> v) { this.entrypoint = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
        public Builder command(List<String> v) { this.command = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
        public Builder workingDir(String v) { this.workingDir = v; return this; }
        public Builder user(String v) { this.user = v; return this; }
        public Builder env(Map<String, String> v) { this.env = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v); return this; }
        public Builder labels(Map<String, String> v) { this.labels = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v); return this; }
        public Builder ports(List<PortBinding> v) { this.ports = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
        public Builder mounts(List<Mount> v) { this.mounts = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
        public Builder capabilities(List<String> v) { this.capabilities = v == null ? new ArrayList<>() : new ArrayList<>(v); return this; }
        public Builder privileged(boolean v) { this.privileged = v; return this; }
        public Builder readOnlyRootfs(boolean v) { this.readOnlyRootfs = v; return this; }
        public Builder origins(Map<String, FieldOrigin> v) { this.origins = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v); return this; }
        public Builder trace(String path, FieldOrigin origin) { this.origins.put(path, origin); return this; }

        public ResolvedContainerConfig build() {
            return new ResolvedContainerConfig(image, platform, imageOs, imageArch,
                    entrypoint, command, workingDir, user, env, labels,
                    ports, mounts, capabilities, privileged, readOnlyRootfs, origins);
        }
    }

    /** 内部小工具：构造确定性有序 map。 */
    private static final class Collections {
        static <K extends Comparable<K>, V> Map<K, V> sorted(Map<K, V> in) {
            return new TreeMap<>(in);
        }
    }
}
