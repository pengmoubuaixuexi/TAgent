package cn.bugstack.ai.types.job.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 任务调度器配置属性
 * @author Fuzhengwei bugstack.cn @小傅哥
 */
@ConfigurationProperties(prefix = "xfg.wrench.task.job", ignoreInvalidFields = true)
public class TaskJobAutoProperties {

    /** 是否启用任务调度器 */
    private boolean enabled = true;
    
    /** 线程池大小 */
    private int poolSize = 10;
    
    /** 线程名称前缀 */
    private String threadNamePrefix = "xfg-task-scheduler-";
    
    /** 关闭时等待任务完成 */
    private boolean waitForTasksToCompleteOnShutdown = true;
    
    /** 等待终止时间（秒） */
    private int awaitTerminationSeconds = 60;
    
    /** 任务刷新间隔（毫秒） */
    private long refreshInterval = 60000;
    
    /** 清理无效任务的cron表达式 */
    private String cleanInvalidTasksCron = "0 0/10 * * * ?";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }

    public boolean isWaitForTasksToCompleteOnShutdown() {
        return waitForTasksToCompleteOnShutdown;
    }

    public void setWaitForTasksToCompleteOnShutdown(boolean waitForTasksToCompleteOnShutdown) {
        this.waitForTasksToCompleteOnShutdown = waitForTasksToCompleteOnShutdown;
    }

    public int getAwaitTerminationSeconds() {
        return awaitTerminationSeconds;
    }

    public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }

    public long getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(long refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public String getCleanInvalidTasksCron() {
        return cleanInvalidTasksCron;
    }

    public void setCleanInvalidTasksCron(String cleanInvalidTasksCron) {
        this.cleanInvalidTasksCron = cleanInvalidTasksCron;
    }
}