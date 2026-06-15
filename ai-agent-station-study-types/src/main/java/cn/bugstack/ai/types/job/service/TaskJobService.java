package cn.bugstack.ai.types.job.service;

import cn.bugstack.ai.types.job.model.TaskScheduleVO;
import cn.bugstack.ai.types.job.provider.ITaskDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 任务调度服务实现类
 *
 * @author @小傅哥
 */
public class TaskJobService implements ITaskJobService, DisposableBean {

    private final Logger log = LoggerFactory.getLogger(TaskJobService.class);

    private final TaskScheduler taskScheduler;
    private final List<ITaskDataProvider> taskDataProviders;

    /**
     * 任务ID与任务执行器的映射，用于记录已添加的任务
     */
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * 新的构造函数，不依赖ITaskExecutor
     */
    public TaskJobService(TaskScheduler taskScheduler,
                         List<ITaskDataProvider> taskDataProviders) {
        this.taskScheduler = taskScheduler;
        this.taskDataProviders = taskDataProviders;
    }
    
    @Override
    public void initializeTasks() {
        log.info("开始初始化任务调度配置");
        try {
            // 聚合所有数据提供者的任务调度配置
            List<TaskScheduleVO> allTaskSchedules = new ArrayList<>();
            for (ITaskDataProvider provider : taskDataProviders) {
                List<TaskScheduleVO> taskSchedules = provider.queryAllValidTaskSchedule();
                if (taskSchedules != null) {
                    allTaskSchedules.addAll(taskSchedules);
                }
            }
            
            // 处理每个任务调度配置
            for (TaskScheduleVO task : allTaskSchedules) {
                // 创建并调度新任务
                scheduleTask(task);
            }
            
            log.info("任务调度配置初始化完成，已加载任务数: {}", scheduledTasks.size());
        } catch (Exception e) {
            log.error("初始化任务调度配置时发生错误", e);
        }
    }

    @Override
    public boolean addTask(TaskScheduleVO task) {
        try {
            if (task == null || task.getId() == null) {
                log.warn("任务配置为空或任务ID为空，无法添加任务");
                return false;
            }

            // 如果任务已存在，先移除旧任务
            if (scheduledTasks.containsKey(task.getId())) {
                log.info("任务已存在，先移除旧任务，ID: {}", task.getId());
                removeTask(task.getId());
            }

            // 调度新任务
            scheduleTask(task);

            log.info("任务添加成功，ID: {}, 描述: {}", task.getId(), task.getDescription());
            return true;
        } catch (Exception e) {
            log.error("添加任务时发生错误，ID: {}", task != null ? task.getId() : "null", e);
            return false;
        }
    }

    @Override
    public boolean removeTask(Long taskId) {
        try {
            if (taskId == null) {
                log.warn("任务ID为空，无法移除任务");
                return false;
            }

            ScheduledFuture<?> future = scheduledTasks.remove(taskId);
            if (future != null) {
                future.cancel(true);
                log.info("任务移除成功，ID: {}", taskId);
                return true;
            } else {
                log.warn("未找到要移除的任务，ID: {}", taskId);
                return false;
            }
        } catch (Exception e) {
            log.error("移除任务时发生错误，ID: {}", taskId, e);
            return false;
        }
    }

    /**
     * 调度单个任务
     */
    private void scheduleTask(TaskScheduleVO task) {
        try {
            log.info("开始调度任务，ID: {}, 描述: {}, Cron表达式: {}", task.getId(), task.getDescription(), task.getCronExpression());

            // 使用新的函数式编程方式
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> executeTaskWithFunction(task),
                    new CronTrigger(task.getCronExpression())
            );

            scheduledTasks.put(task.getId(), future);

            log.info("任务调度成功（函数式），ID: {}", task.getId());
        } catch (Exception e) {
            log.error("调度任务时发生错误，ID: {}", task.getId(), e);
        }
    }

    /**
     * 使用函数式编程方式执行任务
     */
    private void executeTaskWithFunction(TaskScheduleVO task) {
        try {
            log.info("开始执行任务（函数式），ID: {}, 描述: {}", task.getId(), task.getDescription());

            // 获取并执行任务
            Runnable taskRunnable = task.getTaskExecutor().get();
            taskRunnable.run();

            log.info("任务执行完成（函数式），ID: {}", task.getId());
        } catch (Exception e) {
            log.error("执行任务时发生错误（函数式），ID: {}", task.getId(), e);
        }
    }
    
    @Override
    public void refreshTasks() {
        log.info("开始刷新任务调度配置（动态更新）");
        try {
            // 聚合所有数据提供者的任务调度配置
            List<TaskScheduleVO> allTaskSchedules = new ArrayList<>();
            for (ITaskDataProvider provider : taskDataProviders) {
                List<TaskScheduleVO> taskSchedules = provider.queryAllValidTaskSchedule();
                if (taskSchedules != null) {
                    allTaskSchedules.addAll(taskSchedules);
                }
            }

            // 记录当前配置中的任务ID
            Map<Long, Boolean> currentTaskIds = new ConcurrentHashMap<>();

            // 处理每个任务调度配置
            for (TaskScheduleVO task : allTaskSchedules) {
                Long taskId = task.getId();
                currentTaskIds.put(taskId, true);

                // 如果任务已经存在，则跳过
                if (scheduledTasks.containsKey(taskId)) {
                    continue;
                }

                // 创建并调度新任务
                scheduleTask(task);
            }

            // 移除已不存在的任务
            scheduledTasks.keySet().removeIf(taskId -> {
                if (!currentTaskIds.containsKey(taskId)) {
                    ScheduledFuture<?> future = scheduledTasks.remove(taskId);
                    if (future != null) {
                        future.cancel(true);
                        log.info("已移除任务，ID: {}", taskId);
                    }
                    return true;
                }
                return false;
            });

            log.info("任务调度配置刷新完成，当前活跃任务数: {}", scheduledTasks.size());
        } catch (Exception e) {
            log.error("刷新任务调度配置时发生错误", e);
        }
    }

    @Override
    public void cleanInvalidTasks() {
        log.info("开始清理无效的任务");
        try {
            // 聚合所有数据提供者的无效任务ID
            List<Long> allInvalidTaskIds = new ArrayList<>();
            for (ITaskDataProvider provider : taskDataProviders) {
                List<Long> invalidTaskIds = provider.queryAllInvalidTaskScheduleIds();
                if (invalidTaskIds != null) {
                    allInvalidTaskIds.addAll(invalidTaskIds);
                }
            }
            
            if (allInvalidTaskIds.isEmpty()) {
                log.info("没有发现无效的任务需要清理");
                return;
            }
            
            log.info("发现{}个无效任务需要清理", allInvalidTaskIds.size());
            
            // 从调度器中移除这些任务
            for (Long taskId : allInvalidTaskIds) {
                ScheduledFuture<?> future = scheduledTasks.remove(taskId);
                if (future != null) {
                    future.cancel(true);
                    log.info("已移除无效任务，ID: {}", taskId);
                }
            }
            
            log.info("无效任务清理完成，当前活跃任务数: {}", scheduledTasks.size());
        } catch (Exception e) {
            log.error("清理无效任务时发生错误", e);
        }
    }

    @Override
    public void stopAllTasks() {
        log.info("开始停止所有任务");
        scheduledTasks.forEach((id, future) -> {
            if (future != null) {
                future.cancel(true);
                log.info("已取消任务，ID: {}", id);
            }
        });
        scheduledTasks.clear();
        log.info("所有任务已停止");
    }

    @Override
    public int getActiveTaskCount() {
        return scheduledTasks.size();
    }

    @Override
    public void destroy() {
        stopAllTasks();
    }

}