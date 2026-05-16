package com.baimao.oj.service.impl;

import com.baimao.oj.config.RabbitMqConfig;
import com.baimao.oj.mapper.ContestRankSnapshotMapper;
import com.baimao.oj.model.entity.ContestRankSnapshot;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rabbitmq.client.Channel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Date;

/**
 * 通过 RabbitMQ 异步同步比赛排行榜快照表。
 */
@Service
@Slf4j
// @Deprecated
public class ContestRankSnapshotSyncService {

    @Resource
    private ContestRankSnapshotMapper contestRankSnapshotMapper;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 投递排行榜快照同步消息。
     *
     * <p>方法名保留 Async 语义，实际异步能力由 RabbitMQ 承担，避免本地线程池任务在进程异常时丢失。
     */
    public void syncSnapshotAsync(ContestRankSnapshot snapshot) {
        if (!isValidSnapshot(snapshot)) {
            return;
        }
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.CONTEST_RANK_SNAPSHOT_EXCHANGE,
                    RabbitMqConfig.CONTEST_RANK_SNAPSHOT_ROUTING_KEY,
                    snapshot
            );
        } catch (AmqpException e) {
            log.warn("send contest rank snapshot sync message failed, contestId={}, userId={}",
                    snapshot.getContestId(), snapshot.getUserId(), e);
            throw e;
        }
    }

    /**
     * 消费排行榜快照同步消息，并写入数据库快照表。
     *
     * <p>使用手动确认：只有消息完成校验、幂等判断或成功写库后才 ack；写库异常时 nack 到死信队列。
     */
    @RabbitListener(queues = RabbitMqConfig.CONTEST_RANK_SNAPSHOT_QUEUE)
    public void consumeSnapshotSyncMessage(ContestRankSnapshot snapshot, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            if (!isValidSnapshot(snapshot)) {
                log.warn("discard invalid contest rank snapshot sync message, deliveryTag={}", deliveryTag);
                channel.basicAck(deliveryTag, false);
                return;
            }
            syncSnapshotToDb(snapshot);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.warn("consume contest rank snapshot sync message failed, contestId={}, userId={}, deliveryTag={}",
                    snapshot == null ? null : snapshot.getContestId(),
                    snapshot == null ? null : snapshot.getUserId(),
                    deliveryTag,
                    e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 将排行榜快照以 upsert 方式写入数据库。
     *
     * <p>同一个用户的排行榜快照会持续变化，不能因为快照记录已存在就跳过。
     * <p>这里用 snapshotTime 作为快照版本：更新的快照允许覆盖旧快照，重复消息或旧消息只确认不回写。
     */
    private void syncSnapshotToDb(ContestRankSnapshot snapshot) {
        normalizeSnapshotTime(snapshot);
        ContestRankSnapshot existedSnapshot = contestRankSnapshotMapper.selectOne(
                Wrappers.<ContestRankSnapshot>lambdaQuery()
                        .eq(ContestRankSnapshot::getContestId, snapshot.getContestId())
                        .eq(ContestRankSnapshot::getUserId, snapshot.getUserId())
                        .last("limit 1")
        );
        if (existedSnapshot == null) {
            contestRankSnapshotMapper.insert(snapshot);
            return;
        }
        if (isStaleSnapshot(snapshot, existedSnapshot)) {
            log.info("skip stale contest rank snapshot message, contestId={}, userId={}, messageSnapshotTime={}, dbSnapshotTime={}",
                    snapshot.getContestId(),
                    snapshot.getUserId(),
                    snapshot.getSnapshotTime(),
                    existedSnapshot.getSnapshotTime());
            return;
        }
        snapshot.setId(existedSnapshot.getId());
        contestRankSnapshotMapper.update(
                snapshot,
                Wrappers.<ContestRankSnapshot>lambdaUpdate()
                        .eq(ContestRankSnapshot::getId, existedSnapshot.getId())
                        // 防止旧消息在查询之后、新消息写库之后再次覆盖新快照。
                        .and(wrapper -> wrapper
                                .isNull(ContestRankSnapshot::getSnapshotTime)
                                .or()
                                .le(ContestRankSnapshot::getSnapshotTime, snapshot.getSnapshotTime()))
        );
    }

    /**
     * 校验快照是否具备同步所需的比赛 ID 与用户 ID。
     */
    private boolean isValidSnapshot(ContestRankSnapshot snapshot) {
        return snapshot != null && snapshot.getContestId() != null && snapshot.getUserId() != null;
    }

    /**
     * 兜底补齐快照时间，保证后续可以按快照版本判断新旧。
     */
    private void normalizeSnapshotTime(ContestRankSnapshot snapshot) {
        if (snapshot.getSnapshotTime() == null) {
            snapshot.setSnapshotTime(new Date());
        }
    }

    /**
     * 判断消息快照是否比数据库快照更旧。
     * 即便先来的消息消费失败，后面的消息消费成功，也不影响数据库最终的排行，
     * 因为消息来源于redis，redis是事实排行榜，前面的消息是旧快照，不应该再执行了
     */
    private boolean isStaleSnapshot(ContestRankSnapshot messageSnapshot, ContestRankSnapshot dbSnapshot) {
        Date messageSnapshotTime = messageSnapshot.getSnapshotTime();
        Date dbSnapshotTime = dbSnapshot.getSnapshotTime();
        return messageSnapshotTime != null && dbSnapshotTime != null && messageSnapshotTime.before(dbSnapshotTime);
    }
}
