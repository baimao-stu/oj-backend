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
 * AI 工具日调用计数实体。
 */
@Data
@TableName("ai_tool_call_log")
public class AiToolCallLog implements Serializable {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * OJ 用户编号。
     */
    private Long userId;

    /**
     * 工具唯一名称。
     */
    private String toolName;

    /**
     * 日期键，格式 yyyy-MM-dd。
     */
    private String callDate;

    /**
     * 当日调用次数。
     */
    private Integer callCount;

    /**
     * 记录创建时间。
     */
    private Date createTime;

    /**
     * 记录更新时间。
     */
    private Date updateTime;

    /**
     * 逻辑删除标记。
     */
    @TableLogic
    private Byte isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}

