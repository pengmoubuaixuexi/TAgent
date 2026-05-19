package cn.bugstack.ai.domain.agent.service.router;

import cn.bugstack.ai.domain.agent.model.valobj.IntentVO;

/**
 * 意图路由器（P0.1.1）。
 * <p>
 * 用户 query 进门第一站，把 query 分类成 chat/qa/tool/plan/sensitive 五大类。
 * 当前为 shadow mode：只采集，不改变 dispatch 行为。
 */
public interface IIntentRouter {

    /**
     * 同步分类（在调用线程内调 LLM；调用方自行决定是否走异步）。
     * 失败/未配置/低置信度都会返回 UNKNOWN 而非抛异常，调用方据此决定回退。
     */
    IntentVO classify(String query);
}
