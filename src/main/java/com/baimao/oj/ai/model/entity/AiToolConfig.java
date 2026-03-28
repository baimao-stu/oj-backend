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
 * 按工具维度的启用与限流配置实体。
 */
@Data
@TableName("ai_tool_config")
public class AiToolConfig implements Serializable {

    /**
     * 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 工具唯一名称。
     */
    private String toolName;

    /**
     * 启用标记。
     */
    private Integer enabled;

    /**
     * 每用户每日调用上限。
     */
    private Integer dailyLimit;

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

