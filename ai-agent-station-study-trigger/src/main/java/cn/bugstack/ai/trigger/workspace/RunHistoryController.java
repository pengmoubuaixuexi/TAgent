package cn.bugstack.ai.trigger.workspace;

import cn.bugstack.ai.domain.agent.adapter.repository.IRunHistoryRepository;
import cn.bugstack.ai.trigger.security.ConversationAccess;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static cn.bugstack.ai.trigger.workspace.WorkspaceController.*;

@RestController
@RequestMapping(value="/api/v1/history",produces="application/json")
public class RunHistoryController {
    private final IRunHistoryRepository history;
    private final ConversationAccess conversations;
    public RunHistoryController(IRunHistoryRepository history,ConversationAccess conversations){this.history=history;this.conversations=conversations;}
    @GetMapping public Object list(Authentication a,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="30") int limit){return ok(history.listOwned(user(a),offset,limit));}
    @GetMapping("/session/{sessionId}") public Object session(Authentication a,@PathVariable String sessionId,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        String owner=user(a);
        if(sessionId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"缺少会话标识");
        conversations.session(sessionId,owner,false);
        return ok(history.listOwnedSession(owner,sessionId,offset,limit));
    }
    @GetMapping("/{runId}") public Object get(Authentication a,@PathVariable String runId){return ok(history.findOwned(user(a),runId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"运行不存在或无权访问")));}
}
