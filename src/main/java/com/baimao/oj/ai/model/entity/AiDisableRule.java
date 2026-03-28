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
 * AI 禁用策略规则实体。
 */
@Data
@TableName("ai_disable_rule")
public class AiDisableRule implements Serializable {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 作用域类型常量：GLOBAL/CONTEST/QUESTION/USER。
     */
    private String scopeType;

    /**
     * 作用域编号；GLOBAL 通常为 0。
     */
    private Long scopeId;

    /**
     * 可读禁用原因。
     */
    private String reason;

    /**
     * 规则开始时间，可为空。
     */
    private Date startTime;

    /**
     * 规则结束时间，可为空。
     */
    private Date endTime;

    /**
     * 启用标记。
     */
    private Integer enabled;

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

