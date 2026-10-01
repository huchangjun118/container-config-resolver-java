package com.github.huchangjun118.containerconfigresolverjava.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个来源提交的原始配置。所有字段都是 Presence：
 * 来源可以"完全不提某字段"（absent，合并时跳过），
 * 也可以"显式提交"（present，含 null/空列表/空 map 这种有语义的值）。
 *
 * <p>容器类型的合并语义：env/labels 做字段级（按 key）合并；
 * ports/mounts/capabilities 做列表追加去重；
 * entrypoint/command 作为不可分割的整体替换（外加 Docker CMD 重置语义）；
 * 其余标量字段整体替换。</p>
 */
public final class RawContainerConfig {

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<String> image = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<String> platform = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<List<String>> entrypoint = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<List<String>> command = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<String> workingDir = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<String> user = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<Map<String, String>> env = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<Map<String, String>> labels = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<List<PortBinding>> ports = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<List<Mount>> mounts = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<List<String>> capabilities = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<Boolean> privileged = Presence.absent();

    @JsonDeserialize(using = PresenceDeserializer.class)
    private Presence<Boolean> readOnlyRootfs = Presence.absent();

    public RawContainerConfig() {
    }

    public Presence<String> getImage() { return image; }
    public void setImage(Presence<String> image) { this.image = image; }

    public Presence<String> getPlatform() { return platform; }
    public void setPlatform(Presence<String> platform) { this.platform = platform; }

    public Presence<List<String>> getEntrypoint() { return entrypoint; }
    public void setEntrypoint(Presence<List<String>> entrypoint) { this.entrypoint = entrypoint; }

    public Presence<List<String>> getCommand() { return command; }
    public void setCommand(Presence<List<String>> command) { this.command = command; }

    public Presence<String> getWorkingDir() { return workingDir; }
    public void setWorkingDir(Presence<String> workingDir) { this.workingDir = workingDir; }

    public Presence<String> getUser() { return user; }
    public void setUser(Presence<String> user) { this.user = user; }

    public Presence<Map<String, String>> getEnv() { return env; }
    public void setEnv(Presence<Map<String, String>> env) { this.env = env; }

    public Presence<Map<String, String>> getLabels() { return labels; }
    public void setLabels(Presence<Map<String, String>> labels) { this.labels = labels; }

    public Presence<List<PortBinding>> getPorts() { return ports; }
    public void setPorts(Presence<List<PortBinding>> ports) { this.ports = ports; }

    public Presence<List<Mount>> getMounts() { return mounts; }
    public void setMounts(Presence<List<Mount>> mounts) { this.mounts = mounts; }

    public Presence<List<String>> getCapabilities() { return capabilities; }
    public void setCapabilities(Presence<List<String>> capabilities) { this.capabilities = capabilities; }

    public Presence<Boolean> getPrivileged() { return privileged; }
    public void setPrivileged(Presence<Boolean> privileged) { this.privileged = privileged; }

    public Presence<Boolean> getReadOnlyRootfs() { return readOnlyRootfs; }
    public void setReadOnlyRootfs(Presence<Boolean> readOnlyRootfs) { this.readOnlyRootfs = readOnlyRootfs; }

    /** 便捷构造器，链式提交字段。 */
    public static Builder builder() {
        return new Builder();
    }

    /** 空配置：所有字段均 absent，可作为模板缺省值。 */
    public static RawContainerConfig empty() {
        return new RawContainerConfig();
    }

    public static final class Builder {
        private final RawContainerConfig c = new RawContainerConfig();

        public Builder image(String v) { c.image = Presence.present(v); return this; }
        public Builder platform(String v) { c.platform = Presence.present(v); return this; }
        public Builder entrypoint(List<String> v) { c.entrypoint = Presence.present(v); return this; }
        public Builder command(List<String> v) { c.command = Presence.present(v); return this; }
        public Builder workingDir(String v) { c.workingDir = Presence.present(v); return this; }
        public Builder user(String v) { c.user = Presence.present(v); return this; }
        public Builder env(Map<String, String> v) {
            c.env = Presence.present(v == null ? null : new LinkedHashMap<>(v));
            return this;
        }
        public Builder labels(Map<String, String> v) {
            c.labels = Presence.present(v == null ? null : new LinkedHashMap<>(v));
            return this;
        }
        public Builder ports(List<PortBinding> v) { c.ports = Presence.present(v); return this; }
        public Builder mounts(List<Mount> v) { c.mounts = Presence.present(v); return this; }
        public Builder capabilities(List<String> v) { c.capabilities = Presence.present(v); return this; }
        public Builder privileged(boolean v) { c.privileged = Presence.present(v); return this; }
        public Builder readOnlyRootfs(boolean v) { c.readOnlyRootfs = Presence.present(v); return this; }

        public RawContainerConfig build() {
            return c;
        }
    }
}
