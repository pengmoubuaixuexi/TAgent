package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * ArmoryApi 装配请求 DTO
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/1/15 10:00
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ArmoryApiRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * API配置ID
     */
    private String apiId;

}