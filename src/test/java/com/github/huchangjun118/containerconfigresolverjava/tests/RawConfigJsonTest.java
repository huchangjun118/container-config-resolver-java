package com.github.huchangjun118.containerconfigresolverjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.huchangjun118.containerconfigresolverjava.model.RawContainerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawConfigJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingKeyIsAbsent() throws Exception {
        RawContainerConfig c = mapper.readValue("{}", RawContainerConfig.class);
        assertTrue(c.getUser().isAbsent());
        assertTrue(c.getEntrypoint().isAbsent());
        assertTrue(c.getEnv().isAbsent());
    }

    @Test
    void explicitNullIsPresentWithNullValue() throws Exception {
        RawContainerConfig c = mapper.readValue("{\"user\": null}", RawContainerConfig.class);
        assertTrue(c.getUser().isPresent());
        assertNull(c.getUser().value());
    }

    @Test
    void emptyArrayIsPresentWithEmptyList() throws Exception {
        RawContainerConfig c = mapper.readValue("{\"entrypoint\": []}", RawContainerConfig.class);
        assertTrue(c.getEntrypoint().isPresent());
        assertEquals(List.of(), c.getEntrypoint().value());
    }

    @Test
    void mapsAndScalarsDeserialize() throws Exception {
        RawContainerConfig c = mapper.readValue(
                "{\"user\":\"u\",\"privileged\":true,"
                        + "\"env\":{\"K\":\"V\"},\"readOnlyRootfs\":false}",
                RawContainerConfig.class);
        assertEquals("u", c.getUser().value());
        assertTrue(c.getPrivileged().value());
        assertEquals(false, c.getReadOnlyRootfs().value());
        assertEquals("V", c.getEnv().value().get("K"));
    }
}
