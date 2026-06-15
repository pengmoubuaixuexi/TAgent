package cn.bugstack.ai.types.job.model;

import lombok.Data;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 任务调度值对象
 * @author @小傅哥
 */
@Data
public class TaskScheduleVO {

    /** 任务ID */
    private Long id;
    
    /** 任务描述 */
    private String description;
    
    /** Cron表达式 */
    private String cronExpression;
    
    /** 任务参数 */
    private String taskParam;
    
    /** 任务执行器函数式接口 */
    private Supplier<Runnable> taskExecutor;

    public TaskScheduleVO() {
    }

    /**
     * 便捷方法：设置任务执行逻辑
     * @param taskLogic 任务执行逻辑
     */
    public void setTaskLogic(Runnable taskLogic) {
        this.taskExecutor = () -> taskLogic;
    }
    
    /**
     * 便捷方法：设置带参数的任务执行逻辑
     * @param taskLogic 任务执行逻辑，接收taskId和taskParam
     */
    public void setTaskLogic(BiConsumer<Long, String> taskLogic) {
        this.taskExecutor = () -> () -> taskLogic.accept(this.id, this.taskParam);
    }

    @Override
    public String toString() {
        return "TaskScheduleVO{" +
                "id=" + id +
                ", description='" + description + '\'' +
                ", cronExpression='" + cronExpression + '\'' +
                ", taskParam='" + taskParam + '\'' +
                ", hasTaskExecutor=" + (taskExecutor != null) +
                '}';
    }
}