package cn.bugstack.ai.domain.agent.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库接口服务
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/10/4 09:11
 */
public interface IRagService {

    void storeRagFile(String name, String tag, List<MultipartFile> files);

    void storeRagFile(String name, String tag, List<MultipartFile> files, String userId);

}
