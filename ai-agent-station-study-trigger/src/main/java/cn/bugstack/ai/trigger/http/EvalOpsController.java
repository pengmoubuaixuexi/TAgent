package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.trigger.eval.EvalDatasetService;
import cn.bugstack.ai.trigger.eval.EvalCodeVersionService;
import cn.bugstack.ai.trigger.eval.EvalJudgeService;
import cn.bugstack.ai.trigger.eval.EvalRunService;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/eval")
@CrossOrigin(origins = "*", allowedHeaders = "*")
public class EvalOpsController {

    private final EvalDatasetService datasetService;
    private final EvalRunService runService;
    private final EvalJudgeService judgeService;
    private final EvalCodeVersionService codeVersionService;

    @GetMapping("/datasets")
    public Response<List<Map<String, Object>>> datasets(
            @RequestParam(required = false) String ownerUserId,
            @RequestParam(defaultValue = "100") int limit) {
        return execute(() -> datasetService.listDatasets(ownerUserId, limit));
    }

    @PostMapping("/datasets")
    public Response<Map<String, Object>> createDataset(@RequestBody DatasetRequest request) {
        return execute(() -> datasetService.createDataset(request.getName(), request.getDescription(),
                request.getExecutionMode(), request.getOwnerUserId()));
    }

    @GetMapping("/datasets/{datasetId}")
    public Response<Map<String, Object>> dataset(@PathVariable String datasetId) {
        return execute(() -> datasetService.getDataset(datasetId));
    }

    @DeleteMapping("/datasets/{datasetId}")
    public Response<Boolean> deleteDataset(@PathVariable String datasetId) {
        return execute(() -> { datasetService.deleteDataset(datasetId); return true; });
    }

    @PostMapping("/datasets/{datasetId}/update")
    public Response<Map<String, Object>> updateDataset(@PathVariable String datasetId,
                                                        @RequestBody DatasetRequest request) {
        return execute(() -> datasetService.updateDataset(datasetId, request.getName(), request.getDescription(), request.getExecutionMode()));
    }

    @GetMapping("/datasets/{datasetId}/versions")
    public Response<List<Map<String, Object>>> versions(@PathVariable String datasetId) {
        return execute(() -> datasetService.listVersions(datasetId));
    }

    @GetMapping("/versions/{versionId}/cases")
    public Response<List<Map<String, Object>>> cases(@PathVariable String versionId,
                                                     @RequestParam(defaultValue = "false") boolean enabledOnly) {
        return execute(() -> datasetService.listCases(versionId, enabledOnly));
    }

    @PostMapping("/datasets/{datasetId}/cases")
    public Response<Map<String, Object>> createCase(@PathVariable String datasetId,
                                                     @RequestBody EvalDatasetService.CaseCommand command) {
        return execute(() -> datasetService.saveCase(datasetId, null, command));
    }

    @PostMapping("/datasets/{datasetId}/cases/{caseId}")
    public Response<Map<String, Object>> updateCase(@PathVariable String datasetId,
                                                     @PathVariable String caseId,
                                                     @RequestBody EvalDatasetService.CaseCommand command) {
        return execute(() -> datasetService.saveCase(datasetId, caseId, command));
    }

    @DeleteMapping("/datasets/{datasetId}/cases/{caseId}")
    public Response<Boolean> deleteCase(@PathVariable String datasetId, @PathVariable String caseId) {
        return execute(() -> { datasetService.deleteCase(datasetId, caseId); return true; });
    }

    @PostMapping("/datasets/{datasetId}/publish")
    public Response<Map<String, Object>> publish(@PathVariable String datasetId,
                                                  @RequestBody(required = false) PublishRequest request) {
        return execute(() -> datasetService.publish(datasetId, request == null ? null : request.getDescription()));
    }

    @PostMapping("/datasets/import/e2e100")
    public Response<Map<String, Object>> importE2E100(@RequestBody(required = false) ImportRequest request) {
        return execute(() -> datasetService.importE2E100(request == null ? null : request.getOwnerUserId()));
    }

    @PostMapping("/datasets/import/quality-benchmark")
    public Response<Map<String, Object>> importQualityBenchmark(@RequestBody(required = false) ImportRequest request) {
        return execute(() -> datasetService.importQualityBenchmark(request == null ? null : request.getOwnerUserId()));
    }

    @PostMapping("/runs")
    public Response<Map<String, Object>> createRun(@RequestBody EvalRunService.RunCommand command) {
        codeVersionService.reconcilePendingBindings();
        return execute(() -> runService.createRun(command));
    }

    @GetMapping("/runs")
    public Response<List<Map<String, Object>>> runs(@RequestParam(required = false) String datasetId,
                                                    @RequestParam(defaultValue = "100") int limit) {
        return execute(() -> runService.listRuns(datasetId, limit));
    }

    @GetMapping("/runs/{evalRunId}")
    public Response<Map<String, Object>> run(@PathVariable String evalRunId) {
        return execute(() -> runService.getRun(evalRunId));
    }

    @PostMapping("/runs/{evalRunId}/cancel")
    public Response<Map<String, Object>> cancelRun(@PathVariable String evalRunId) {
        return execute(() -> {
            judgeService.cancelRun(evalRunId);
            return runService.cancelRun(evalRunId);
        });
    }

    @DeleteMapping("/runs/{evalRunId}")
    public Response<Boolean> deleteRun(@PathVariable String evalRunId) {
        return execute(() -> {
            judgeService.cancelRun(evalRunId);
            runService.deleteRun(evalRunId);
            return true;
        });
    }

    @GetMapping("/runs/{evalRunId}/results")
    public Response<List<Map<String, Object>>> results(@PathVariable String evalRunId,
                                                       @RequestParam(defaultValue = "false") boolean includeTrace) {
        return execute(() -> runService.listResults(evalRunId, includeTrace));
    }

    @GetMapping("/runs/{evalRunId}/results/{resultId}")
    public Response<Map<String, Object>> result(@PathVariable String evalRunId, @PathVariable String resultId) {
        return execute(() -> runService.getResult(evalRunId, resultId));
    }

    @GetMapping("/code-tags")
    public Response<List<Map<String, Object>>> codeTags(@RequestParam(defaultValue = "100") int limit) {
        return execute(() -> codeVersionService.listTags(limit));
    }

    @PostMapping("/runs/{evalRunId}/code-version/bind")
    public Response<Map<String, Object>> bindCodeVersion(@PathVariable String evalRunId,
                                                         @RequestBody EvalCodeVersionService.BindTagCommand command) {
        return execute(() -> codeVersionService.bindTag(evalRunId, command));
    }

    @PostMapping("/runs/{evalRunId}/judge")
    public Response<Map<String, Object>> judge(@PathVariable String evalRunId,
                                               @RequestBody(required = false) EvalJudgeService.JudgeCommand command) {
        return execute(() -> judgeService.createJudgeJob(evalRunId, command));
    }

    @GetMapping("/runs/{evalRunId}/judge-jobs")
    public Response<List<Map<String, Object>>> judgeJobs(@PathVariable String evalRunId) {
        return execute(() -> judgeService.listJobs(evalRunId));
    }

    @GetMapping("/judge-jobs/{judgeJobId}")
    public Response<Map<String, Object>> judgeJob(@PathVariable String judgeJobId) {
        return execute(() -> judgeService.getJob(judgeJobId));
    }

    private <T> Response<T> execute(Action<T> action) {
        try {
            return Response.<T>builder().code(ResponseCode.SUCCESS.getCode()).info("success").data(action.run()).build();
        } catch (IllegalArgumentException error) {
            return Response.<T>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(error.getMessage()).build();
        } catch (Exception error) {
            log.error("EvalOps request failed", error);
            return Response.<T>builder().code(ResponseCode.UN_ERROR.getCode())
                    .info(error.getMessage() == null ? "EvalOps 操作失败" : error.getMessage()).build();
        }
    }

    @FunctionalInterface
    private interface Action<T> { T run() throws Exception; }

    @Data
    public static class DatasetRequest {
        private String name;
        private String description;
        private String executionMode;
        private String ownerUserId;
    }

    @Data
    public static class PublishRequest { private String description; }

    @Data
    public static class ImportRequest { private String ownerUserId; }
}
