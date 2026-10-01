package com.github.huchangjun118.containerconfigresolverjava.config;

import com.github.huchangjun118.containerconfigresolverjava.config.interpolate.Interpolator;
import com.github.huchangjun118.containerconfigresolverjava.config.merge.ConfigMerger;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ConfigSource;
import com.github.huchangjun118.containerconfigresolverjava.config.model.ContainerConfig;
import com.github.huchangjun118.containerconfigresolverjava.config.model.SourcePriority;
import com.github.huchangjun118.containerconfigresolverjava.config.registry.ConfigRegistry;
import com.github.huchangjun118.containerconfigresolverjava.config.registry.ExternalValueProvider;
import com.github.huchangjun118.containerconfigresolverjava.config.validation.CompatibilityValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 注册中心装配：默认来源提供者（基础模板 + 环境覆盖 + 服务覆盖 + 运行时注入），
 * 外部取值默认来自系统环境变量。
 */
@Configuration
public class RegistryConfiguration {

    @Bean
    public ConfigMerger configMerger() {
        return new ConfigMerger();
    }

    @Bean
    public Interpolator interpolator() {
        return new Interpolator();
    }

    @Bean
    public CompatibilityValidator compatibilityValidator() {
        return new CompatibilityValidator();
    }

    @Bean
    public ExternalValueProvider externalValueProvider() {
        return System::getenv;
    }

    @Bean
    public ConfigRegistry configRegistry(ConfigMerger merger,
                                         Interpolator interpolator,
                                         CompatibilityValidator validator,
                                         ExternalValueProvider externalValueProvider) {
        return new ConfigRegistry(
                RegistryConfiguration::defaultSources,
                externalValueProvider,
                merger,
                interpolator,
                validator);
    }

    static List<ConfigSource> defaultSources() {
        return List.of(
                new ConfigSource("base-template", "基础模板", SourcePriority.BASE_TEMPLATE,
                        ContainerConfig.builder()
                                .image("registry.example.com/app/server:1.0.0")
                                .platform("linux/amd64")
                                .entrypoint(List.of("/app/server"))
                                .command(List.of("--port", "8080"))
                                .env(Map.of("LOG_LEVEL", "info"))
                                .build()),
                new ConfigSource("env-default", "环境覆盖", SourcePriority.ENVIRONMENT,
                        ContainerConfig.builder()
                                .env(Map.of("LOG_LEVEL", "warn", "TZ", "UTC"))
                                .build()),
                new ConfigSource("svc-order", "服务级覆盖", SourcePriority.SERVICE,
                        ContainerConfig.builder()
                                .env(Map.of("SERVICE_NAME", "order-service"))
                                .build()),
                new ConfigSource("runtime", "运行时注入", SourcePriority.RUNTIME_INJECTION,
                        ContainerConfig.builder()
                                .build())
        );
    }
}
