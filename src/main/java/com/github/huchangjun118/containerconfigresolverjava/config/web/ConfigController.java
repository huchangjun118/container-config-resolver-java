package com.github.huchangjun118.containerconfigresolverjava.config.web;

import com.github.huchangjun118.containerconfigresolverjava.config.model.ResolvedConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.registry.ConfigRegistry;
import com.github.huchangjun118.containerconfigresolverjava.config.registry.ConfigRegistry.ReloadFailure;
import com.github.huchangjun118.containerconfigresolverjava.config.registry.ConfigRegistry.ReloadResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置查询与重载接口。
 */
@RestController
@RequestMapping("/config")
public class ConfigController {

    private final ConfigRegistry registry;

    public ConfigController(ConfigRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/current")
    public Map<String, Object> current() {
        ResolvedConfig c = registry.current();
        Map<String, Object> body = new LinkedHashMap<>();
        if (c == null) {
            body.put("committed", false);
            return body;
        }
        body.put("committed", true);
        body.put("version", c.version());
        body.put("committedAt", c.committedAt());
        body.put("sources", c.sources());
        body.put("config", c.config());
        body.put("provenance", c.provenance());
        return body;
    }

    @PostMapping("/reload")
    public Map<String, Object> reload() {
        ReloadResult result = registry.reload();
        Map<String, Object> body = new LinkedHashMap<>();
        if (result instanceof ReloadResult.Success s) {
            body.put("success", true);
            body.put("version", s.version());
        } else if (result instanceof ReloadResult.Failure f) {
            body.put("success", false);
            body.put("reason", f.reason());
        }
        return body;
    }

    @GetMapping("/failures")
    public Map<String, Object> failures() {
        ReloadFailure f = registry.lastFailure();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentVersion", registry.currentVersion());
        body.put("lastFailure", f);
        return body;
    }
}
