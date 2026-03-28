package com.baimao.oj.ai.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Tool event payload streamed to frontend in agent mode.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiToolEventVO implements Serializable {
    /**
     * Tool unique name.
     */
    private String toolName;
    /**
     * Execution status: done/skipped/error.
     */
    private String status;
    /**
     * Human-readable summary.
     */
    private String summary;
}
