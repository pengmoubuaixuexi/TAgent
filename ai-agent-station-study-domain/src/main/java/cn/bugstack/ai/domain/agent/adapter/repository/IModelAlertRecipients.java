package cn.bugstack.ai.domain.agent.adapter.repository;

import java.util.List;

/** Who hears about platform model health: every enabled administrator. */
public interface IModelAlertRecipients {
    List<String> administrators();
}
