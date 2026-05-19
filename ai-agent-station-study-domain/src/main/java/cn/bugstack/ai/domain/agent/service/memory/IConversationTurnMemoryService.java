package cn.bugstack.ai.domain.agent.service.memory;

import cn.bugstack.ai.domain.agent.model.entity.ExecuteCommandEntity;

public interface IConversationTurnMemoryService {

    void saveFinalTurn(ExecuteCommandEntity request, String finalOutput);
}
