package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.Task;

/**
 * task 表仓储（蓝图 §7 接口清单）。
 */
public interface TaskRepository {

    /** 插入并回填自增 id 与 DB 生成的 createdAt。 */
    Task insert(Task task);

    Optional<Task> findById(long id);

    List<Task> findAll();

    /** 更新 status 字段（ACTIVE/…）。 */
    void updateStatus(long id, String status);
}
