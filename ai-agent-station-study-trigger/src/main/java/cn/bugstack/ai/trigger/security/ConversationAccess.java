package cn.bugstack.ai.trigger.security;

import cn.bugstack.ai.infrastructure.dao.IConversationOwnerDao;
import cn.bugstack.ai.domain.agent.service.execute.snapshot.RunSnapshotService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationAccess {
    private final IConversationOwnerDao owners;
    private final ObjectProvider<RunSnapshotService> snapshots;
    public ConversationAccess(IConversationOwnerDao owners, ObjectProvider<RunSnapshotService> snapshots) {
        this.owners = owners; this.snapshots = snapshots;
    }
    public void session(String id, String userId, boolean create) {
        if (id == null || id.isBlank()) return;
        if (id.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话标识过长");
        String owner = owners.owner(id);
        if (owner == null && id.startsWith(userId + ":")) {
            owner = owners.owner(id.substring(userId.length() + 1));
        }
        if (owner == null && id.startsWith("default:" + userId + ":")) {
            owner = owners.owner(id.substring(("default:" + userId + ":").length()));
        }
        if (owner == null && create) {
            if (id.contains(":")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新会话标识不能包含冒号");
            owners.claim(id, userId); // Unique key arbitrates simultaneous claims.
            owner = owners.owner(id);
        }
        if (!userId.equals(owner)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该会话");
    }
    public void run(String id, String userId) {
        run(id, userId, false);
    }
    public void run(String id, String userId, boolean create) {
        if (id == null || id.isBlank()) return;
        if (id.length() > 128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行标识过长");
        var service = snapshots.getIfAvailable();
        var snapshot = service == null ? null : service.find(id).orElse(null);
        if (create && snapshot == null) {
            // Reserve client-generated run IDs before dispatch; concurrent users cannot claim the same ID.
            owners.claim("run:" + id, userId);
            if (!userId.equals(owners.owner("run:" + id)))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "运行标识已被占用");
            return;
        }
        if (snapshot == null || !userId.equals(snapshot.getUserId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该运行");
    }
}
