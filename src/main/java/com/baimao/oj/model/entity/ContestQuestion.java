package com.baimao.oj.model.entity;

import com.baimao.oj.model.vo.ContestUserVO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;

import lombok.Builder;
import lombok.Data;
import org.jetbrains.annotations.NotNull;

/**
 * 竞赛题目关联表
 * @TableName contest_question
 */
@TableName(value ="contest_question")
@Data
@Builder
public class ContestQuestion implements Serializable,Comparable<ContestQuestion> {
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

    /**
     * 按序号升序排序
     * @param o the object to be compared.
     * @return
     */
    @Override
    public int compareTo(@NotNull ContestQuestion o) {
        return this.sequence - o.sequence;
    }
}