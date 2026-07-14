package com.springcat.controller;

import com.springcat.model.SystemSnapshot;
import com.springcat.service.SystemMonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API exposing live system metrics as JSON.
 */
@RestController
@RequestMapping("/api")
public class SystemController {

    private final SystemMonitorService monitorService;

    public SystemController(SystemMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/system")
    public SystemSnapshot system() {
        return monitorService.snapshot();
    }
}
