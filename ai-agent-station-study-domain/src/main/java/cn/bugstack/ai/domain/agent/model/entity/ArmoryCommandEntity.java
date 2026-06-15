package cn.bugstack.ai.domain.agent.model.entity;

import cn.bugstack.ai.domain.agent.model.valobj.enums.AiAgentEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 装配命令
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/6/27 07:26
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ArmoryCommandEntity {

    /**
     * 命令类型 AiAgentEnumVO.getCode
     */
    private String commandType;

    /**
     * 命令索引（clientId、modelId、apiId...）
     */
    private List<String> commandIdList;

    /**
     * 根据 commandType 获取对应的数据加载策略字符串。
     * 通过调用 AiAgentEnumVO 枚举类的 getByCode 方法，获取枚举实例，
     * 然后返回该实例的 loadDataStrategy 字段值。
     * <p>
     * 注意：commandType 必须是有效的枚举编码，否则可能引发异常。
     *
     * @return 返回对应的加载数据策略字符串
     */
    public String getLoadDataStrategy() {
        return AiAgentEnumVO.getByCode(commandType).getLoadDataStrategy();
    }

}
