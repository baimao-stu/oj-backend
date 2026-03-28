package com.baimao.oj.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * Entity for daily AI tool call counters.
 */
@Data
@TableName("ai_tool_call_log")
public class AiToolCallLog implements Serializable {

    /**
     * Primary key.
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * OJ user id.
     */
    private Long userId;

    /**
     * Tool unique name.
     */
    private String toolName;

    /**
     * Date key in yyyy-MM-dd format.
     */
    private String callDate;

    /**
     * Number of calls on the date.
     */
    private Integer callCount;

    /**
     * Record creation time.
     */
    private Date createTime;

    /**
     * Record update time.
     */
    private Date updateTime;

    /**
     * Logical deletion flag.
     */
    @TableLogic
    private Byte isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
