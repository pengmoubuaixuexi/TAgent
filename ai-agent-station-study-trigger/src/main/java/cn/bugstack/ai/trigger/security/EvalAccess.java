package cn.bugstack.ai.trigger.security;

import cn.bugstack.ai.infrastructure.dao.IAiEvalOpsDao;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.Map;

/** Dataset ownership also governs its versions, runs, results and judge jobs. */
@Component
public class EvalAccess implements HandlerInterceptor, WebMvcConfigurer {
    private final IAiEvalOpsDao dao;
    public EvalAccess(IAiEvalOpsDao dao) { this.dao = dao; }
    public static String userId() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/eval/**");
    }
    public void dataset(String id) {
        var dataset = dao.findDataset(id);
        if (dataset == null || !userId().equals(dataset.getOwnerUserId())) deny();
    }
    public void version(String id) {
        var version = dao.findVersion(id);
        if (version == null) deny();
        dataset(version.getDatasetId());
    }
    public void run(String id) {
        var run = dao.findRun(id);
        if (run == null) deny();
        dataset(run.getDatasetId());
    }
    @Override @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars != null) {
            if (vars.containsKey("datasetId")) dataset(vars.get("datasetId"));
            if (vars.containsKey("versionId")) version(vars.get("versionId"));
            if (vars.containsKey("evalRunId")) run(vars.get("evalRunId"));
            if (vars.containsKey("judgeJobId")) {
                var job = dao.findJudgeJob(vars.get("judgeJobId"));
                if (job == null) deny();
                run(job.getEvalRunId());
            }
        }
        if (request.getParameter("datasetId") != null && !request.getParameter("datasetId").isBlank())
            dataset(request.getParameter("datasetId"));
        return true;
    }
    private static void deny() { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该评测数据"); }
}
