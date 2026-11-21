package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.TopRoutineResponse;
import com.copymylife.copymylife.service.TopRoutineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/top-routines")
public class TopRoutineController {

    private final TopRoutineService topRoutineService;

    public TopRoutineController(
            TopRoutineService topRoutineService
    ) {
        this.topRoutineService = topRoutineService;
    }

    @GetMapping
    public List<TopRoutineResponse> getTopRoutines(
            @RequestParam(defaultValue = "week") String period,
            @RequestParam(defaultValue = "overall") String sort,
            @RequestParam(defaultValue = "all") String category,
            @RequestParam(defaultValue = "all") String time
    ) {
        return topRoutineService.getTopRoutines(
                period,
                sort,
                category,
                time
        );
    }
}