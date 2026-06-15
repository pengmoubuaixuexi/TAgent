package cn.bugstack.ai.trigger.http.admin;

import cn.bugstack.ai.api.IAiClientSystemPromptAdminService;
import cn.bugstack.ai.api.dto.AiClientSystemPromptQueryRequestDTO;
import cn.bugstack.ai.api.dto.AiClientSystemPromptRequestDTO;
import cn.bugstack.ai.api.dto.AiClientSystemPromptResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.infrastructure.dao.IAiClientSystemPromptDao;
import cn.bugstack.ai.infrastructure.dao.po.AiClientSystemPrompt;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 系统提示词配置管理控制器
 *
 * @author bugstack虫洞栈
 * @description 系统提示词配置管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/ai-client-system-prompt")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class AiClientSystemPromptAdminController implements IAiClientSystemPromptAdminService {

    @Resource
    private IAiClientSystemPromptDao aiClientSystemPromptDao;

    @Override
    @PostMapping("/create")
    public Response<Boolean> createAiClientSystemPrompt(@RequestBody AiClientSystemPromptRequestDTO request) {
        try {
            log.info("创建系统提示词配置请求：{}", request);
            
            // DTO转PO
            AiClientSystemPrompt aiClientSystemPrompt = convertToAiClientSystemPrompt(request);
            aiClientSystemPrompt.setCreateTime(LocalDateTime.now());
            aiClientSystemPrompt.setUpdateTime(LocalDateTime.now());
            
            aiClientSystemPromptDao.insert(aiClientSystemPrompt);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(true)
                    .build();
        } catch (Exception e) {
            log.error("创建系统提示词配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-id")
    public Response<Boolean> updateAiClientSystemPromptById(@RequestBody AiClientSystemPromptRequestDTO request) {
        try {
            log.info("根据ID更新系统提示词配置请求：{}", request);
            
            if (request.getId() == null) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientSystemPrompt aiClientSystemPrompt = convertToAiClientSystemPrompt(request);
            aiClientSystemPrompt.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientSystemPromptDao.updateById(aiClientSystemPrompt);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID更新系统提示词配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @PutMapping("/update-by-prompt-id")
    public Response<Boolean> updateAiClientSystemPromptByPromptId(@RequestBody AiClientSystemPromptRequestDTO request) {
        try {
            log.info("根据提示词ID更新系统提示词配置请求：{}", request);
            
            if (!StringUtils.hasText(request.getPromptId())) {
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("提示词ID不能为空")
                        .data(false)
                        .build();
            }
            
            // DTO转PO
            AiClientSystemPrompt aiClientSystemPrompt = convertToAiClientSystemPrompt(request);
            aiClientSystemPrompt.setUpdateTime(LocalDateTime.now());
            
            int result = aiClientSystemPromptDao.updateByPromptId(aiClientSystemPrompt);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据提示词ID更新系统提示词配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-id/{id}")
    public Response<Boolean> deleteAiClientSystemPromptById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID删除系统提示词配置：{}", id);
            
            int result = aiClientSystemPromptDao.deleteById(id);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据ID删除系统提示词配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @DeleteMapping("/delete-by-prompt-id/{promptId}")
    public Response<Boolean> deleteAiClientSystemPromptByPromptId(@PathVariable("promptId") String promptId) {
        try {
            log.info("根据提示词ID删除系统提示词配置：{}", promptId);
            
            int result = aiClientSystemPromptDao.deleteByPromptId(promptId);
            
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result > 0)
                    .build();
        } catch (Exception e) {
            log.error("根据提示词ID删除系统提示词配置失败", e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(false)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-id/{id}")
    public Response<AiClientSystemPromptResponseDTO> queryAiClientSystemPromptById(@PathVariable("id") Long id) {
        try {
            log.info("根据ID查询系统提示词配置：{}", id);
            
            AiClientSystemPrompt aiClientSystemPrompt = aiClientSystemPromptDao.queryById(id);
            
            if (aiClientSystemPrompt == null) {
                return Response.<AiClientSystemPromptResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("数据不存在")
                        .data(null)
                        .build();
            }
            
            AiClientSystemPromptResponseDTO responseDTO = convertToAiClientSystemPromptResponseDTO(aiClientSystemPrompt);
            
            return Response.<AiClientSystemPromptResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据ID查询系统提示词配置失败", e);
            return Response.<AiClientSystemPromptResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-prompt-id/{promptId}")
    public Response<AiClientSystemPromptResponseDTO> queryAiClientSystemPromptByPromptId(@PathVariable("promptId") String promptId) {
        try {
            log.info("根据提示词ID查询系统提示词配置：{}", promptId);
            
            AiClientSystemPrompt aiClientSystemPrompt = aiClientSystemPromptDao.queryByPromptId(promptId);
            
            if (aiClientSystemPrompt == null) {
                return Response.<AiClientSystemPromptResponseDTO>builder()
                        .code(ResponseCode.UN_ERROR.getCode())
                        .info("系统提示词配置不存在")
                        .data(null)
                        .build();
            }
            
            AiClientSystemPromptResponseDTO responseDTO = convertToAiClientSystemPromptResponseDTO(aiClientSystemPrompt);
            
            return Response.<AiClientSystemPromptResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (Exception e) {
            log.error("根据提示词ID查询系统提示词配置失败", e);
            return Response.<AiClientSystemPromptResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-all")
    public Response<List<AiClientSystemPromptResponseDTO>> queryAllAiClientSystemPrompts() {
        try {
            log.info("查询所有系统提示词配置");
            
            List<AiClientSystemPrompt> aiClientSystemPrompts = aiClientSystemPromptDao.queryAll();
            
            List<AiClientSystemPromptResponseDTO> responseDTOs = aiClientSystemPrompts.stream()
                    .map(this::convertToAiClientSystemPromptResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientSystemPromptResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询所有系统提示词配置失败", e);
            return Response.<List<AiClientSystemPromptResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-enabled")
    public Response<List<AiClientSystemPromptResponseDTO>> queryEnabledAiClientSystemPrompts() {
        try {
            log.info("查询启用的系统提示词配置");
            
            List<AiClientSystemPrompt> aiClientSystemPrompts = aiClientSystemPromptDao.queryEnabledPrompts();
            
            List<AiClientSystemPromptResponseDTO> responseDTOs = aiClientSystemPrompts.stream()
                    .map(this::convertToAiClientSystemPromptResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientSystemPromptResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("查询启用的系统提示词配置失败", e);
            return Response.<List<AiClientSystemPromptResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @GetMapping("/query-by-prompt-name/{promptName}")
    public Response<List<AiClientSystemPromptResponseDTO>> queryAiClientSystemPromptsByPromptName(@PathVariable("promptName") String promptName) {
        try {
            log.info("根据提示词名称查询系统提示词配置：{}", promptName);
            
            List<AiClientSystemPrompt> aiClientSystemPrompts = aiClientSystemPromptDao.queryByPromptName(promptName);
            
            List<AiClientSystemPromptResponseDTO> responseDTOs = aiClientSystemPrompts.stream()
                    .map(this::convertToAiClientSystemPromptResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientSystemPromptResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据提示词名称查询系统提示词配置失败", e);
            return Response.<List<AiClientSystemPromptResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    @Override
    @PostMapping("/query-list")
    public Response<List<AiClientSystemPromptResponseDTO>> queryAiClientSystemPromptList(@RequestBody AiClientSystemPromptQueryRequestDTO request) {
        try {
            log.info("根据条件查询系统提示词配置列表：{}", request);
            
            // 根据查询条件构建查询逻辑
            List<AiClientSystemPrompt> aiClientSystemPrompts;
            
            if (StringUtils.hasText(request.getPromptId())) {
                // 根据提示词ID查询
                AiClientSystemPrompt prompt = aiClientSystemPromptDao.queryByPromptId(request.getPromptId());
                aiClientSystemPrompts = prompt != null ? List.of(prompt) : List.of();
            } else if (StringUtils.hasText(request.getPromptName())) {
                // 根据提示词名称查询
                aiClientSystemPrompts = aiClientSystemPromptDao.queryByPromptName(request.getPromptName());
            } else if (request.getStatus() != null) {
                // 根据状态查询
                if (request.getStatus() == 1) {
                    aiClientSystemPrompts = aiClientSystemPromptDao.queryEnabledPrompts();
                } else {
                    // 查询所有然后过滤
                    aiClientSystemPrompts = aiClientSystemPromptDao.queryAll().stream()
                            .filter(prompt -> prompt.getStatus().equals(request.getStatus()))
                            .collect(Collectors.toList());
                }
            } else {
                // 查询所有
                aiClientSystemPrompts = aiClientSystemPromptDao.queryAll();
            }
            
            // 应用状态过滤（如果有其他条件的话）
            if (request.getStatus() != null && !StringUtils.hasText(request.getPromptId()) && !StringUtils.hasText(request.getPromptName())) {
                aiClientSystemPrompts = aiClientSystemPrompts.stream()
                        .filter(prompt -> prompt.getStatus().equals(request.getStatus()))
                        .collect(Collectors.toList());
            }
            
            List<AiClientSystemPromptResponseDTO> responseDTOs = aiClientSystemPrompts.stream()
                    .map(this::convertToAiClientSystemPromptResponseDTO)
                    .collect(Collectors.toList());
            
            return Response.<List<AiClientSystemPromptResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOs)
                    .build();
        } catch (Exception e) {
            log.error("根据条件查询系统提示词配置列表失败", e);
            return Response.<List<AiClientSystemPromptResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .data(null)
                    .build();
        }
    }

    /**
     * DTO转PO对象
     */
    private AiClientSystemPrompt convertToAiClientSystemPrompt(AiClientSystemPromptRequestDTO requestDTO) {
        AiClientSystemPrompt aiClientSystemPrompt = new AiClientSystemPrompt();
        BeanUtils.copyProperties(requestDTO, aiClientSystemPrompt);
        return aiClientSystemPrompt;
    }

    /**
     * PO转DTO对象
     */
    private AiClientSystemPromptResponseDTO convertToAiClientSystemPromptResponseDTO(AiClientSystemPrompt aiClientSystemPrompt) {
        AiClientSystemPromptResponseDTO responseDTO = new AiClientSystemPromptResponseDTO();
        BeanUtils.copyProperties(aiClientSystemPrompt, responseDTO);
        return responseDTO;
    }

}