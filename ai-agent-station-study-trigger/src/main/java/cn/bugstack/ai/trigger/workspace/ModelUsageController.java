package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.infrastructure.security.JdbcModelUsageAccounting;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workspace/usage")
public class ModelUsageController {
    private final JdbcModelUsageAccounting usage;
    public ModelUsageController(JdbcModelUsageAccounting usage){this.usage=usage;}
    @GetMapping public Object usage(Authentication authentication,@RequestParam(required=false) String runId){
        return WorkspaceController.ok(usage.overview(WorkspaceController.user(authentication),runId));
    }
}
