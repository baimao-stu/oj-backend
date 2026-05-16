package com.baimao.oj.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;

/**
 * RabbitMQ 配置。
 *
 * <p>用于声明比赛排行榜快照同步所需的交换机、队列、死信队列和 JSON 消息转换器。
 */
@Configuration
@EnableRabbit
public class RabbitMqConfig {

    /** 比赛排行榜快照同步交换机。 */
    public static final String CONTEST_RANK_SNAPSHOT_EXCHANGE = "oj.contest.rank.snapshot.exchange";

    /** 比赛排行榜快照同步路由键。 */
    public static final String CONTEST_RANK_SNAPSHOT_ROUTING_KEY = "oj.contest.rank.snapshot.sync";

    /** 比赛排行榜快照同步队列。 */
    public static final String CONTEST_RANK_SNAPSHOT_QUEUE = "oj.contest.rank.snapshot.queue";

    /** 比赛排行榜快照同步死信交换机。 */
    public static final String CONTEST_RANK_SNAPSHOT_DLX = "oj.contest.rank.snapshot.dlx";

    /** 比赛排行榜快照同步死信路由键。 */
    public static final String CONTEST_RANK_SNAPSHOT_DLQ_ROUTING_KEY = "oj.contest.rank.snapshot.sync.dlq";

    /** 比赛排行榜快照同步死信队列。 */
    public static final String CONTEST_RANK_SNAPSHOT_DLQ = "oj.contest.rank.snapshot.dlq";

    /**
     * 声明排行榜快照同步交换机。
     */
    @Bean
    public DirectExchange contestRankSnapshotExchange() {
        return new DirectExchange(CONTEST_RANK_SNAPSHOT_EXCHANGE, true, false);
    }

    /**
     * 声明排行榜快照同步死信交换机。
     */
    @Bean
    public DirectExchange contestRankSnapshotDeadLetterExchange() {
        return new DirectExchange(CONTEST_RANK_SNAPSHOT_DLX, true, false);
    }

    /**
     * 声明排行榜快照同步队列，并把消费失败的消息转入死信交换机。
     */
    @Bean
    public Queue contestRankSnapshotQueue() {
        return QueueBuilder.durable(CONTEST_RANK_SNAPSHOT_QUEUE)
                .deadLetterExchange(CONTEST_RANK_SNAPSHOT_DLX)
                .deadLetterRoutingKey(CONTEST_RANK_SNAPSHOT_DLQ_ROUTING_KEY)
                .build();
    }

    /**
     * 声明排行榜快照同步死信队列，用于保留多次消费失败的消息。
     */
    @Bean
    public Queue contestRankSnapshotDeadLetterQueue() {
        return QueueBuilder.durable(CONTEST_RANK_SNAPSHOT_DLQ).build();
    }

    /**
     * 绑定排行榜快照同步队列到业务交换机。
     */
    @Bean
    public Binding contestRankSnapshotBinding() {
        return BindingBuilder.bind(contestRankSnapshotQueue())
                .to(contestRankSnapshotExchange())
                .with(CONTEST_RANK_SNAPSHOT_ROUTING_KEY);
    }

    /**
     * 绑定排行榜快照死信队列到死信交换机。
     */
    @Bean
    public Binding contestRankSnapshotDeadLetterBinding() {
        return BindingBuilder.bind(contestRankSnapshotDeadLetterQueue())
                .to(contestRankSnapshotDeadLetterExchange())
                .with(CONTEST_RANK_SNAPSHOT_DLQ_ROUTING_KEY);
    }

    /**
     * 使用项目统一的 ObjectMapper 序列化 RabbitMQ 消息，避免 Java 原生序列化带来的兼容性问题。
     */
    @Bean
    public MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
