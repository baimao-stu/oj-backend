package com.baimao.oj.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 用户竞赛报名表
 * @TableName registrations
 */
@TableName(value ="registrations")
@Data
public class Registrations implements Serializable {
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
     * 用户ID（关联user.id）
     */
    private Long userId;

    /**
     * 报名时间
     */
    private Date joinTime;

    /**
     * 比赛排名
     */
    private Integer rank;

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