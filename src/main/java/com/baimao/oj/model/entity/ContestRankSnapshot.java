package com.baimao.oj.model.entity;

import com.baimao.oj.judge.codesangbox.model.JudgeInfo;
import com.baimao.oj.model.vo.ContestUserVO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;

/**
 * 比赛排行榜快照。
 *
 * Redis 是实时榜单真源，该表仅保存异步刷库后的聚合快照。
 */
@TableName(value = "contest_rank_snapshot", autoResultMap = true)
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
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<Long, JudgeInfo> questionStatus;

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

    /**
     * 每道题最后一次提交的元数据，仅用于 Redis 快照恢复，不落库
     */
    @JsonIgnore
    @TableField(exist = false)
    private Map<Long, ContestUserVO.QuestionLastSubmitMeta> questionLastSubmitMeta;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
