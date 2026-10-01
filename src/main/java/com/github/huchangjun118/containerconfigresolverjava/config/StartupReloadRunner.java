package com.github.huchangjun118.containerconfigresolverjava.config;

import com.github.huchangjun118.containerconfigresolverjava.config.registry.ConfigRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动完成后触发首次重载。即使首次重载失败，服务仍正常启动（上一版本为空，读取返回未提交状态）。
 */
@Component
public class StartupReloadRunner {

    private final ConfigRegistry registry;

    public StartupReloadRunner(ConfigRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        registry.reload();
    }
}
