package cn.bugstack.ai.domain.agent.service.model;

/** Runtime bridge for static wire guards; production installs the durable implementation at startup. */
public final class ModelUsageLedger {
    private static volatile ModelUsageAccounting accounting;
    private ModelUsageLedger() {}
    public static void install(ModelUsageAccounting service) { accounting=java.util.Objects.requireNonNull(service); }
    public static void uninstall(ModelUsageAccounting service) { if(accounting==service)accounting=null; }
    public static boolean enabled() {return accounting!=null;}
    public static ModelUsageAccounting.Ticket begin(ModelUsageAccounting.Attempt attempt) {
        var service=accounting;return service==null?null:service.begin(attempt);
    }
    public static void openRun(String runId,String owner,long deadline) {
        var service=accounting;if(service!=null)service.openRun(runId,owner,deadline,ModelInvocationScope.isSystemOwner(owner));
    }
    public static void changeRunState(String runId,String owner,String state) {
        var service=accounting;if(service!=null)service.changeRunState(runId,owner,state);
    }
}
