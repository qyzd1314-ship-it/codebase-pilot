package com.yupi.yuaiagent.controller;

import com.yupi.yuaiagent.eval.EvalRunnerService;
import com.yupi.yuaiagent.eval.dto.EvalReportDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/evals")
public class EvalController {

    private final EvalRunnerService evalRunnerService;

    public EvalController(EvalRunnerService evalRunnerService) {
        this.evalRunnerService = evalRunnerService;
    }

    @PostMapping("/run")
    public EvalReportDto runEval() {
        return evalRunnerService.runDefaultEval();
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalState(IllegalStateException e) {
        return Map.of("message", e.getMessage());
    }
}
