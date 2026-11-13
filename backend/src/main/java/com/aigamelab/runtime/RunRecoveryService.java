package com.aigamelab.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * P1-4：启动恢复扫描（蓝图 §9.5）。
 * 启动时扫描 RUNNING Run 续跑；WAITING_APPROVAL 保持挂起等审批 API 唤醒。
 * 全部状态已落库 → 恢复即"重放 observe + 续跑"，零内存快照。
 */
@Component
public class RunRecoveryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RunRecoveryService.class);

    private final RunControlService controlService;

    public RunRecoveryService(RunControlService controlService) {
        this.controlService = controlService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int resumed = controlService.recoverOnStartup();
        if (resumed > 0) {
            log.info("启动恢复：{} 个 RUNNING Run 已续跑（WAITING_APPROVAL 保持挂起）", resumed);
        }
    }
}
