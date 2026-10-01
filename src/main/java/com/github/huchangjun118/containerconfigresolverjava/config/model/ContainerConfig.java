package com.github.huchangjun118.containerconfigresolverjava.config.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.List;
import java.util.Map;

/**
 * 容器运行配置片段。不可变对象。
 *
 * <p>字段为 {@code null} 表示该来源未指定；非 null 表示显式指定（空集合表示显式重置）。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public final class ContainerConfig {

    private final String image;
    private final String platform;
    private final String arch;
    private final List<String> entrypoint;
    private final List<String> command;
    private final Map<String, String> env;
    private final Map<String, String> labels;
    private final List<PortMapping> ports;
    private final List<VolumeMount> volumes;
    private final Boolean privileged;
    private final Boolean readOnlyRootFilesystem;
    private final String workingDir;
    private final String user;

    private ContainerConfig(Builder b) {
        this.image = b.image;
        this.platform = b.platform;
        this.arch = b.arch;
        this.entrypoint = b.entrypoint == null ? null : List.copyOf(b.entrypoint);
        this.command = b.command == null ? null : List.copyOf(b.command);
        this.env = b.env == null ? null : Map.copyOf(b.env);
        this.labels = b.labels == null ? null : Map.copyOf(b.labels);
        this.ports = b.ports == null ? null : List.copyOf(b.ports);
        this.volumes = b.volumes == null ? null : List.copyOf(b.volumes);
        this.privileged = b.privileged;
        this.readOnlyRootFilesystem = b.readOnlyRootFilesystem;
        this.workingDir = b.workingDir;
        this.user = b.user;
    }

    public String image() { return image; }
    public String platform() { return platform; }
    public String arch() { return arch; }
    public List<String> entrypoint() { return entrypoint; }
    public List<String> command() { return command; }
    public Map<String, String> env() { return env; }
    public Map<String, String> labels() { return labels; }
    public List<PortMapping> ports() { return ports; }
    public List<VolumeMount> volumes() { return volumes; }
    public Boolean privileged() { return privileged; }
    public Boolean readOnlyRootFilesystem() { return readOnlyRootFilesystem; }
    public String workingDir() { return workingDir; }
    public String user() { return user; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String image;
        private String platform;
        private String arch;
        private List<String> entrypoint;
        private List<String> command;
        private Map<String, String> env;
        private Map<String, String> labels;
        private List<PortMapping> ports;
        private List<VolumeMount> volumes;
        private Boolean privileged;
        private Boolean readOnlyRootFilesystem;
        private String workingDir;
        private String user;

        public Builder image(String image) { this.image = image; return this; }
        public Builder platform(String platform) { this.platform = platform; return this; }
        public Builder arch(String arch) { this.arch = arch; return this; }
        public Builder entrypoint(List<String> entrypoint) { this.entrypoint = entrypoint; return this; }
        public Builder command(List<String> command) { this.command = command; return this; }
        public Builder env(Map<String, String> env) { this.env = env; return this; }
        public Builder labels(Map<String, String> labels) { this.labels = labels; return this; }
        public Builder ports(List<PortMapping> ports) { this.ports = ports; return this; }
        public Builder volumes(List<VolumeMount> volumes) { this.volumes = volumes; return this; }
        public Builder privileged(Boolean privileged) { this.privileged = privileged; return this; }
        public Builder readOnlyRootFilesystem(Boolean readOnlyRootFilesystem) { this.readOnlyRootFilesystem = readOnlyRootFilesystem; return this; }
        public Builder workingDir(String workingDir) { this.workingDir = workingDir; return this; }
        public Builder user(String user) { this.user = user; return this; }

        public ContainerConfig build() { return new ContainerConfig(this); }
    }
}
