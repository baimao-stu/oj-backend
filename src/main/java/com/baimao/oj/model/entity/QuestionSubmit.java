package com.baimao.oj.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 题目提交
 * @TableName question_submit
 */
@TableName(value ="question_submit")
@Data
public class QuestionSubmit implements Serializable {
    /**
     * id
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 题目 id
     */
    private Long questionId;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 编程语言
     */
    private String language;

    /**
     * 用户代码
     */
    private String code;

    /**
     * 判题信息（json数组，用了多少时间、内存）
     */
    private String judgeInfo;

    /**
     * 判题状态（0-带判题，1-判题中，2-成功，3-失败）
     */
    private Integer status;

    /**
     * 答案错误时的测试用例
     */
    private String errorCase;

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

    /**
     * 竞赛ID（contest.id，可为NULL表示非竞赛提交）
     */
    private Long contestId;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}