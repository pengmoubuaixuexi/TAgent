package cn.bugstack.ai.infrastructure.dao.po;

import lombok.Data;

@Data
public class DailyRegistrationCount {
    private String day;
    private long count;
}
