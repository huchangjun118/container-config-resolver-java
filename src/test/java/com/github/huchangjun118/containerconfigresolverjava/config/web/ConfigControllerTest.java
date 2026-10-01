package com.github.huchangjun118.containerconfigresolverjava.config.web;

import com.github.huchangjun118.containerconfigresolverjava.config.RegistryConfiguration;
import com.github.huchangjun118.containerconfigresolverjava.config.registry.ConfigRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigController.class)
@Import({RegistryConfiguration.class})
class ConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigRegistry registry;

    @Test
    void currentBeforeReload_notCommitted() throws Exception {
        mockMvc.perform(get("/config/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(false));
    }

    @Test
    void reloadThenCurrent_returnsCommittedVersion() throws Exception {
        mockMvc.perform(post("/config/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/config/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.config.image").exists())
                .andExpect(jsonPath("$.provenance.image.sourceId").exists());
    }

    @Test
    void failuresEndpoint_exposesCurrentVersion() throws Exception {
        mockMvc.perform(get("/config/failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(0));
    }
}
