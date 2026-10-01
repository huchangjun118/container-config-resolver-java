package com.github.huchangjun118.containerconfigresolverjava.web;

import com.github.huchangjun118.containerconfigresolverjava.model.CommittedSnapshot;
import com.github.huchangjun118.containerconfigresolverjava.model.ConfigSourceVersion;
import com.github.huchangjun118.containerconfigresolverjava.model.ResolveFailure;
import com.github.huchangjun118.containerconfigresolverjava.resolver.ConfigResolver;
import com.github.huchangjun118.containerconfigresolverjava.web.dto.FailureView;
import com.github.huchangjun118.containerconfigresolverjava.web.dto.ReloadRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 只读解析接口：
 * GET  /resolver/config   当前已提交快照（最终定义、字段追溯、校验结论）
 * GET  /resolver/failure  最近一次失败原因（成功提交后清空）
 * POST /resolver/reload   提交一个完整来源版本并求值；
 *                         校验/插值失败返回 422，且不影响已生效版本
 */
@RestController
@RequestMapping("/resolver")
public class ConfigController {

    private final ConfigResolver resolver;

    public ConfigController(ConfigResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/config")
    public ResponseEntity<CommittedSnapshot> current() {
        return resolver.current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/failure")
    public ResponseEntity<FailureView> failure() {
        return resolver.lastFailure()
                .map(f -> ResponseEntity.ok(FailureView.of(f)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/reload")
    public ResponseEntity<?> reload(@RequestBody ReloadRequest request) {
        List<ConfigSourceVersion.LayeredSource> layers =
                request.layers() == null ? List.of()
                : request.layers().stream()
                        .map(l -> new ConfigSourceVersion.LayeredSource(
                                l.layer(), l.name(),
                                l.config() == null
                                        ? com.github.huchangjun118.containerconfigresolverjava.model.RawContainerConfig.empty()
                                        : l.config()))
                        .toList();
        ConfigSourceVersion version = new ConfigSourceVersion(
                request.revision(), request.image(), layers);
        Map<String, String> externals = request.externals() == null
                ? Map.of() : request.externals();

        Optional<CommittedSnapshot> result = resolver.reload(version, externals);
        if (result.isPresent()) {
            return ResponseEntity.ok(result.get());
        }
        ResolveFailure failure = resolver.lastFailure().orElse(null);
        return ResponseEntity.unprocessableEntity()
                .body(failure == null ? Map.of("message", "reload failed")
                        : FailureView.of(failure));
    }
}
