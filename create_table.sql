# 数据库初始化

-- 创建库
create database if not exists yuoj;

-- 切换库
use yuoj;

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    unionId      varchar(256)                           null comment '微信开放平台id',
    mpOpenId     varchar(256)                           null comment '公众号openId',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin/ban',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除',
    index idx_unionId (unionId)
) comment '用户' collate = utf8mb4_unicode_ci;

-- 题目表
# drop table question;
create table if not exists question
(
    id          bigint auto_increment comment 'id' primary key,
    title       varchar(512)                       not null comment '标题',
    content     text                               not null comment '内容',
    tags        varchar(1024)                      null comment '标签列表（json 数组）',
    answer      text                               null comment '题目答案',
    subNum      int      default 0                 not null comment '题目提交数',
    acceptedNum int      default 0                 not null comment '题目通过数',
    judgeCase   text                               not null comment '判题用例（json数组）',
    judgeConfig text                               not null comment '判题配置，如时间空间限制（json数组）',
    thumbNum    int      default 0                 not null comment '点赞数',
    favourNum   int      default 0                 not null comment '收藏数',
    userId      bigint                             not null comment '用户 id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    index idx_userId (userId)
) comment '题目' collate = utf8mb4_unicode_ci;

-- 题目提交表
# drop table question_submit;
create table if not exists question_submit
(
    id         bigint auto_increment comment 'id' primary key,
    language   varchar(128)                       not null comment '编程语言',
    code       text                               not null comment '用户代码',
    judgeInfo  text                               null comment '判题信息（json数组）',
    status     int      default 0                 not null comment '判题状态（0-带判题，1-判题中，2-成功，3-失败）',
    questionId bigint                             not null comment '题目 id',
    userId     bigint                             not null comment '创建用户 id',
    errorCase  text                               null comment '答案错误时的测试用例',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    contestId  tinyint                            null comment '竞赛ID（contest.id，可为NULL表示非竞赛提交）',
    index idx_postId (questionId),
    index idx_userId (userId)
) comment '题目提交';

# 默认添加一个管理员账户admin，密码12345678（加密）
insert into user(userAccount, userPassword, userName, userRole)
values ('admin', 'b2da29e9d11a96e368c9ca52cc815218', 'admin', 'admin');


-- 竞赛表
create table if not exists contest
(
    id          bigint auto_increment comment 'id' primary key,
    title       varchar(512)                       not null comment '竞赛标题',
    description text                               null comment '竞赛描述（非必要，可为NULL）',
    type        INT                                not null comment '竞赛类型（0-基础、1-提高、2-进阶）',
    isPublic    INT      default 0                 not null comment '是否公开（0-不公开、1-公开）',
    pLimit      int                                not null comment '上限人数',
    startTime   datetime                           not null comment '开始时间',
    endTime     datetime                           not null comment '结束时间',
    userId      bigint                             not null comment '创建用户 id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    index idx_userId (userId)
) comment '竞赛表' collate = utf8mb4_unicode_ci;

-- 竞赛题目关联表
create table if not exists contest_question
(
    id         bigint auto_increment comment 'id' primary key,
    contestId  bigint                             not null comment '竞赛ID（关联contest.id）',
    questionId bigint                             not null comment '题目ID（关联question.id）',
    sequence   int                                not null comment '题目序号（在竞赛中的顺序）',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    index idx_questionId (questionId),
    index idx_userId (contestId)
) comment '竞赛题目关联表' collate = utf8mb4_unicode_ci;


-- 用户竞赛报名表
create table if not exists registrations
(
    id         bigint auto_increment comment 'id' primary key,
    contestId  bigint                             not null comment '竞赛 ID（关联 contest.id）',
    userId     bigint                             not null comment '用户 ID（关联 user.id）',
    joinTime   datetime                           not null comment '报名时间',
    `rank`       int                                not null comment '比赛排名',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    index idx_userId (userId)
) comment '用户竞赛报名表' collate = utf8mb4_unicode_ci;

-- AI 会话表
create table if not exists ai_chat_session
(
    id              bigint auto_increment comment 'id' primary key,
    userId          bigint                             not null comment '用户 id',
    questionId      bigint                             not null comment '题目 id',
    contestId       bigint   default 0                 not null comment '竞赛 id（0 表示非竞赛）',
    mode            varchar(32) default 'normal'       not null comment '模式：normal/agent',
    status          int      default 0                 not null comment '会话状态：0进行中 1归档 2禁用',
    disableReason   varchar(512)                       null comment '禁用原因',
    lastMessageTime datetime default CURRENT_TIMESTAMP not null comment '最近消息时间',
    expireTime      datetime                           not null comment '过期时间',
    createTime      datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime      datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete        tinyint  default 0                 not null comment '是否删除',
    unique key uk_user_question_contest (userId, questionId, contestId),
    index idx_questionId (questionId),
    index idx_expireTime (expireTime)
) comment 'AI 会话' collate = utf8mb4_unicode_ci;

-- AI 消息表
create table if not exists ai_chat_message
(
    id         bigint auto_increment comment 'id' primary key,
    sessionId  bigint                             not null comment '会话 id',
    role       varchar(32)                        not null comment '角色：user/assistant',
    mode       varchar(32)                        not null comment '模式：normal/agent',
    content    text                               not null comment '消息内容',
    toolCalls  text                               null comment '工具调用记录（json）',
    violation  int      default 0                 not null comment '是否违规（0否 1是）',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    index idx_sessionId (sessionId)
) comment 'AI 消息' collate = utf8mb4_unicode_ci;

-- AI Prompt 配置表
create table if not exists ai_prompt_config
(
    id            bigint auto_increment comment 'id' primary key,
    scene         varchar(64)                        not null comment '场景：normal/agent',
    versionNo     int      default 1                 not null comment '版本号',
    promptContent text                               not null comment '系统提示词',
    enabled       int      default 1                 not null comment '是否启用',
    isActive      int      default 0                 not null comment '是否生效版本',
    createTime    datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime    datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete      tinyint  default 0                 not null comment '是否删除',
    index idx_scene (scene)
) comment 'AI Prompt 配置' collate = utf8mb4_unicode_ci;

-- AI 模型配置表
create table if not exists ai_model_config
(
    id         bigint auto_increment comment 'id' primary key,
    provider   varchar(64)                         not null comment '模型提供商',
    modelName  varchar(128)                        not null comment '模型名',
    baseUrl    varchar(512)                        null comment '模型 baseUrl',
    apiKey     varchar(1024)                       null comment '加密后 apiKey',
    priority   int      default 100                not null comment '优先级（越小越高）',
    enabled    int      default 1                  not null comment '是否启用',
    isDefault  int      default 0                  not null comment '是否默认模型',
    createTime datetime default CURRENT_TIMESTAMP  not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP  not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                  not null comment '是否删除',
    index idx_priority (priority)
) comment 'AI 模型配置' collate = utf8mb4_unicode_ci;

-- AI 禁用规则表
create table if not exists ai_disable_rule
(
    id         bigint auto_increment comment 'id' primary key,
    scopeType  varchar(32)                        not null comment '范围：GLOBAL/CONTEST/QUESTION/USER',
    scopeId    bigint   default 0                 not null comment '范围id（GLOBAL 为 0）',
    reason     varchar(512)                       null comment '禁用原因',
    startTime  datetime                           null comment '生效时间',
    endTime    datetime                           null comment '失效时间',
    enabled    int      default 1                 not null comment '是否启用',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    index idx_scope (scopeType, scopeId)
) comment 'AI 禁用规则' collate = utf8mb4_unicode_ci;

-- AI 敏感词表
create table if not exists ai_sensitive_word
(
    id         bigint auto_increment comment 'id' primary key,
    word       varchar(128)                       not null comment '敏感词',
    enabled    int      default 1                 not null comment '是否启用',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除'
) comment 'AI 敏感词' collate = utf8mb4_unicode_ci;

-- AI 工具配置表
create table if not exists ai_tool_config
(
    id         bigint auto_increment comment 'id' primary key,
    toolName   varchar(64)                        not null comment '工具名',
    enabled    int      default 1                 not null comment '是否启用',
    dailyLimit int      default 30                not null comment '每日调用上限',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    unique key uk_tool_name (toolName)
) comment 'AI 工具配置' collate = utf8mb4_unicode_ci;

-- AI 工具调用计数表
create table if not exists ai_tool_call_log
(
    id         bigint auto_increment comment 'id' primary key,
    userId     bigint                             not null comment '用户 id',
    toolName   varchar(64)                        not null comment '工具名',
    callDate   varchar(16)                        not null comment '调用日期（yyyy-MM-dd）',
    callCount  int      default 0                 not null comment '调用次数',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    unique key uk_user_tool_day (userId, toolName, callDate)
) comment 'AI 工具调用计数' collate = utf8mb4_unicode_ci;

-- AI 违规日志表
create table if not exists ai_violation_log
(
    id             bigint auto_increment comment 'id' primary key,
    userId         bigint                             not null comment '用户 id',
    sessionId      bigint                             null comment '会话 id',
    messageId      bigint                             null comment '消息 id',
    ruleType       varchar(64)                        not null comment '规则类型',
    contentSnippet varchar(512)                       null comment '违规内容摘要',
    createTime     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete       tinyint  default 0                 not null comment '是否删除',
    index idx_userId (userId),
    index idx_ruleType (ruleType)
) comment 'AI 违规日志' collate = utf8mb4_unicode_ci;

insert ignore into ai_tool_config(toolName, enabled, dailyLimit)
values ('submission_analysis', 1, 30),
       ('knowledge_retrieval', 1, 30),
       ('testcase_generator', 1, 30),
       ('sandbox_execute', 1, 30);

insert ignore into ai_prompt_config(scene, versionNo, promptContent, enabled, isActive)
values ('normal', 1, '你是 OJ 学习助手（普通模式）。你只能提供题意理解、思路提示、错误定位、复杂度分析与优化建议，禁止输出可直接通过判题的完整代码。', 1, 1),
       ('agent', 1, '你是 OJ 学习助手（Agent 模式）。你可以基于工具结果给出分析，禁止输出可直接通过判题的完整代码。', 1, 1);



