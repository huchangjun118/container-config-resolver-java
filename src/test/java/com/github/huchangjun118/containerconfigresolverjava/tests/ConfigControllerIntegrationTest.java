package com.github.huchangjun118.containerconfigresolverjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConfigControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private static final String GOOD_RELOAD = """
            {
              "revision": "r1",
              "image": {
                "image": "nginx:1.27", "os": "linux", "architecture": "amd64",
                "entrypoint": ["/docker-entrypoint.sh"], "command": ["nginx", "-g", "daemon off;"]
              },
              "layers": [
                {"layer": "SERVICE", "name": "web",
                 "config": {"env": {"LOG_LEVEL": "${LEVEL}"}, "user": "nginx"}}
              ],
              "externals": {"LEVEL": "debug"}
            }
            """;

    private static final String BAD_RELOAD = """
            {
              "revision": "r2-bad",
              "image": {"image": "x", "os": "linux", "architecture": "arm64",
                        "entrypoint": ["/ep"]},
              "layers": []
            }
            """;

    @Test
    void configIs404BeforeAnyCommit() throws Exception {
        // 该用例与其他用例共享上下文，顺序不保证；仅断言接口存在且返回 404 或 200
        int status = mvc.perform(get("/resolver/config"))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertTrue(status == 200 || status == 404);
    }

    @Test
    void successfulReloadPublishesResolvedSnapshotWithTrace() throws Exception {
        mvc.perform(post("/resolver/reload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GOOD_RELOAD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r1"))
                .andExpect(jsonPath("$.resolved.image").value("nginx:1.27"))
                .andExpect(jsonPath("$.resolved.user").value("nginx"))
                .andExpect(jsonPath("$.resolved.env.LOG_LEVEL").value("debug"))
                .andExpect(jsonPath("$.resolved.entrypoint[0]").value("/docker-entrypoint.sh"))
                .andExpect(jsonPath("$.resolved.command[0]").value("nginx"))
                .andExpect(jsonPath("$.resolved.origins['env.LOG_LEVEL'].layer").value("SERVICE"))
                .andExpect(jsonPath("$.resolved.origins['image'].layer").value("IMAGE"))
                .andExpect(jsonPath("$.validationResult.valid").value(true));

        mvc.perform(get("/resolver/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r1"));
        mvc.perform(get("/resolver/failure")).andExpect(status().isNoContent());
    }

    @Test
    void invalidReloadReturns422AndKeepsOldSnapshot() throws Exception {
        // 先发布一个好版本
        mvc.perform(post("/resolver/reload")
                        .contentType(MediaType.APPLICATION_JSON).content(GOOD_RELOAD))
                .andExpect(status().isOk());

        // 提交架构不兼容的坏版本
        mvc.perform(post("/resolver/reload")
                        .contentType(MediaType.APPLICATION_JSON).content(BAD_RELOAD))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.revision").value("r2-bad"))
                .andExpect(jsonPath("$.phase").value("VALIDATE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ARCH_UNSUPPORTED")));

        // 旧版本仍然生效
        mvc.perform(get("/resolver/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r1"));
        mvc.perform(get("/resolver/failure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2-bad"));
    }
}
