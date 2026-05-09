package com.yupi.yuaiagent.eval;

import com.yupi.yuaiagent.eval.dto.EvalReportDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class EvalCommandLineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCommandLineRunner.class);

    private final EvalRunnerService evalRunnerService;

    public EvalCommandLineRunner(EvalRunnerService evalRunnerService) {
        this.evalRunnerService = evalRunnerService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("eval.run")) {
            return;
        }
        EvalReportDto report = evalRunnerService.runDefaultEval();
        log.info("Eval finished. totalCases={}, totalRuns={}, recallAt5={}, evidenceGroundingRate={}, averageLatencyMs={}, reportPath={}",
                report.getTotalCases(),
                report.getTotalRuns(),
                report.getRecallAt5(),
                report.getEvidenceGroundingRate(),
                report.getAverageLatencyMs(),
                report.getReportPath());
    }
}
