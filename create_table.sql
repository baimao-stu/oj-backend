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
drop table question;
create table if not exists question
(
    id          bigint auto_increment comment 'id' primary key,
    title       varchar(512)                       null comment '标题',
    content     text                               null comment '内容',
    tags        varchar(1024)                      null comment '标签列表（json 数组）',
    answer      text                               null comment '题目答案',
    subNum      int      default 0                 not null comment '题目提交数',
    acceptedNum int      default 0                 not null comment '题目通过数',
    judgeCase   text                               null comment '判题用例（json数组）',
    judgeConfig text                               null comment '判题配置，如时间空间限制（json数组）',
    thumbNum    int      default 0                 not null comment '点赞数',
    favourNum   int      default 0                 not null comment '收藏数',
    userId      bigint                             not null comment '创建用户 id',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    index idx_userId (userId)
) comment '题目' collate = utf8mb4_unicode_ci;

-- 题目提交表
drop table question_submit;
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
    contestId  bigint                             not null comment '竞赛ID（关联contest.id）',
    userId     bigint                             not null comment '用户ID（关联user.id）',
    joinTime   datetime                           not null comment '报名时间',
    rank       int                                not null comment '比赛排名',
    createTime datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete   tinyint  default 0                 not null comment '是否删除',
    index idx_userId (userId)
) comment '用户竞赛报名表' collate = utf8mb4_unicode_ci;




