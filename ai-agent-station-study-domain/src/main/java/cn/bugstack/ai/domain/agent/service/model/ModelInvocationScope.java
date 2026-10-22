package cn.bugstack.ai.domain.agent.service.model;

import cn.bugstack.ai.domain.agent.service.execute.common.RunBudgetRegistry;
import org.slf4j.MDC;
import java.util.Map;
import java.util.UUID;

/** Standalone authenticated model operations share normal run limits and a trusted owner. */
public final class ModelInvocationScope implements AutoCloseable {
    public enum SystemPurpose { STARTUP_CATALOG, ADMIN_EVALUATION, SYSTEM_MAINTENANCE }
    private static final java.util.Set<String> SYSTEM_OWNERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String,String> previous;
    private final String ownedRun;
    private String systemOwner;
    private ModelInvocationScope(RunBudgetRegistry registry, String user, String purpose) {
        this(registry, user, purpose, null);
    }
    private ModelInvocationScope(RunBudgetRegistry registry, String user, String purpose, RunBudgetRegistry.Limits limits) {
        if (user == null || user.isBlank()) throw new SecurityException("缺少模型调用身份");
        previous = MDC.getCopyOfContextMap();
        var existing = RunBudgetRegistry.find(Map.of());
        if (existing != null) {
            if (!user.equals(existing.ownerUserId())) throw new SecurityException("模型调用归属不匹配");
            existing.checkActive();
            ownedRun = null;
        } else {
            ownedRun = "model:" + purpose + ":" + UUID.randomUUID();
            if (limits == null) registry.start(ownedRun, user); else RunBudgetRegistry.start(ownedRun, user, limits);
            MDC.put("runId", ownedRun);
            MDC.put("agent.run_id", ownedRun);
        }
        MDC.put("userId", user);
        MDC.put("modelPurpose", purpose);
    }
    public static ModelInvocationScope open(RunBudgetRegistry registry, String user, String purpose) {
        return new ModelInvocationScope(registry, user, purpose);
    }
    /** Same as {@link #open} but a newly started run uses dedicated limits (e.g. knowledge ingestion). */
    public static ModelInvocationScope open(RunBudgetRegistry registry, String user, String purpose, RunBudgetRegistry.Limits limits) {
        return new ModelInvocationScope(registry, user, purpose, limits);
    }
    /** Only server-owned lifecycle entry points may establish a system-funded operation. */
    public static ModelInvocationScope openSystem(RunBudgetRegistry registry, SystemPurpose purpose) {
        if(RunBudgetRegistry.find(Map.of())!=null)throw new SecurityException("不能将用户运行提升为系统模型调用");
        String owner="system:"+UUID.randomUUID();
        SYSTEM_OWNERS.add(owner);
        try {
            var scope=new ModelInvocationScope(registry,owner,purpose.name());
            scope.systemOwner=owner;
            MDC.remove("userId"); // Resource authorization keeps the original startup (non-user) semantics.
            return scope;
        } catch(RuntimeException failure) {SYSTEM_OWNERS.remove(owner);throw failure;}
    }
    public static boolean isSystemOwner(String user) { return user!=null && SYSTEM_OWNERS.contains(user); }
    public static String currentUser() {
        var budget = RunBudgetRegistry.find(Map.of());
        return budget == null ? MDC.get("userId") : budget.ownerUserId();
    }
    @Override public void close() {
        try {if (ownedRun != null) RunBudgetRegistry.finish(ownedRun);}
        finally {
            if(systemOwner!=null)SYSTEM_OWNERS.remove(systemOwner);
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
