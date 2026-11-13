package com.aigamelab.repository;

import java.util.List;
import java.util.Optional;

import com.aigamelab.domain.DesignDocument;

/**
 * design_document 表仓储（蓝图 §7 接口清单）。
 */
public interface DesignDocumentRepository {

    DesignDocument insert(DesignDocument document);

    Optional<DesignDocument> findById(long id);

    List<DesignDocument> findByTaskId(long taskId);

    List<DesignDocument> findByRunId(long runId);

    void deleteById(long id);

    long count();
}
