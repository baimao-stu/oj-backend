package com.baimao.oj.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

/**
 * 竞赛表
 * @TableName contest
 */
@TableName(value ="contest")
@Data
public class Contest implements Serializable {
    /**
     * id
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 竞赛标题
     */
    private String title;

    /**
     * 竞赛描述（非必要，可为NULL）
     */
    private String description;

    /**
     * 竞赛类型（0-基础、1-提高、2-进阶）
     */
    private Integer type;

    /**
     * 是否公开（0-不公开、1-公开）
     */
    private Integer isPublic;

    /**
     * 上限人数
     */
    private Integer pLimit;

    /**
     * 开始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /**
     * 结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /**
     * 创建用户 id
     */
    private Long userId;

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