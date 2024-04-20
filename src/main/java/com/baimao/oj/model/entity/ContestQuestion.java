package com.baimao.oj.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;

import lombok.Builder;
import lombok.Data;

/**
 * 竞赛题目关联表
 * @TableName contest_question
 */
@TableName(value ="contest_question")
@Data
@Builder
public class ContestQuestion implements Serializable {
    /**
     * id
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 竞赛ID（关联contest.id）
     */
    private Long contestId;

    /**
     * 题目ID（关联question.id）
     */
    private Long questionId;

    /**
     * 题目序号（在竞赛中的顺序）
     */
    private Integer sequence;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    private Byte isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}