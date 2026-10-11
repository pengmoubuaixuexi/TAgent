package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetExceededException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(basePackages={"cn.bugstack.ai.trigger.workspace","cn.bugstack.ai.trigger.knowledge"})
public class ModelQuotaExceptionHandler {
    @ExceptionHandler(RunBudgetExceededException.class)
    public ResponseEntity<?> quota(RunBudgetExceededException failure){
        return ResponseEntity.status(429).body(Map.of("code","429","info",failure.getMessage()));
    }
}
