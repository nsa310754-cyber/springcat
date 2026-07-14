package com.springcat.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.springcat.service.SystemMonitorService;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemController.class)
@Import(SystemMonitorService.class)
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void systemEndpointReturnsSnapshotJson() throws Exception {
        mockMvc.perform(get("/api/system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timestamp").isNumber())
                .andExpect(jsonPath("$.host.availableProcessors").isNumber())
                .andExpect(jsonPath("$.jvm.threadCount").isNumber())
                .andExpect(jsonPath("$.disks").isArray());
    }
}
