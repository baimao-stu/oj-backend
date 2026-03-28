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
 * Entity for sensitive words used by moderation filters.
 */
@Data
@TableName("ai_sensitive_word")
public class AiSensitiveWord implements Serializable {

    /**
     * Primary key.
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Sensitive keyword.
     */
    private String word;

    /**
     * Enabled flag.
     */
    private Integer enabled;

    /**
     * Record creation time.
     */
    private Date createTime;

    /**
     * Record update time.
     */
    private Date updateTime;

    /**
     * Logical deletion flag.
     */
    @TableLogic
    private Byte isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
