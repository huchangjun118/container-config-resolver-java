package com.github.huchangjun118.containerconfigresolverjava.web;

import com.github.huchangjun118.containerconfigresolverjava.interpolation.VariableInterpolator;
import com.github.huchangjun118.containerconfigresolverjava.merge.ConfigMerger;
import com.github.huchangjun118.containerconfigresolverjava.resolver.ConfigResolver;
import com.github.huchangjun118.containerconfigresolverjava.validation.ConfigValidator;
import com.github.huchangjun118.containerconfigresolverjava.validation.HostConstraints;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 装配：默认 HostConstraints 可通过属性覆盖；
 * 生产使用方可提供自己的 HostConstraints bean 描述真实主机事实。
 */
@Configuration
public class ResolverConfig {

    @Bean
    public HostConstraints hostConstraints() {
        // 默认：linux/amd64，无占用端口，允许特权，禁止可写 /proc、/sys
        return new HostConstraints("linux",
                java.util.Set.of("amd64"), java.util.Set.of(), false, false);
    }

    @Bean
    public ConfigMerger configMerger() {
        return new ConfigMerger();
    }

    @Bean
    public VariableInterpolator variableInterpolator() {
        return new VariableInterpolator();
    }

    @Bean
    public ConfigValidator configValidator(HostConstraints constraints) {
        return new ConfigValidator(constraints);
    }

    @Bean
    public ConfigResolver configResolver(ConfigMerger merger,
                                         VariableInterpolator interpolator,
                                         ConfigValidator validator) {
        return new ConfigResolver(merger, interpolator, validator);
    }
}
