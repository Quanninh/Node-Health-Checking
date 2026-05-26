package com.monitoring.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.monitoring.model.*;
import com.monitoring.service.*;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private final ResultStore resultStore;

    public DashboardController(ResultStore resultStore) {
        this.resultStore = resultStore;
    }

    @GetMapping("/result")
    public ResponseEntity<CrackingResponse> getResult() {

        return ResponseEntity.ok(resultStore.getLatest());
    }
}
