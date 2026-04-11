package com.baimao.oj.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 比赛排行榜快照。
 *
 * 该表是排行榜的唯一真源，每个参赛用户在每场比赛中只保留一条聚合快照。
 */
@TableName(value = "contest_rank_snapshot")
@Data
public class ContestRankSnapshot implements Serializable {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 比赛 id
     */
    private Long contestId;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 已通过题目数
     */
    private Integer acceptedNum;

    /**
     * 总耗时，单位毫秒
     */
    private Long totalTime;

    /**
     * 每道题最后一次提交的判题结果，JSON 结构：{"题目id": JudgeInfo}
     */
    private String questionStatus;

    /**
     * 最近一次刷新快照的时间
     */
    private Date snapshotTime;

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
