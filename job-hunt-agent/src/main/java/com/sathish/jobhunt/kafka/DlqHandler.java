package com.sathish.jobhunt.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Dead Letter Queue handler — logs and alerts on unprocessable events.
 * Follows the same DLQ pattern used in production at Sapiens.
 */
@Slf4j
@Component
public class DlqHandler {

    @KafkaListener(topics = {
        "job.discovered.DLT",
        "job.analyzed.DLT",
        "resume.tailored.DLT",
        "application.submitted.DLT"
    }, groupId = "dlq-handler-group")
    public void handleDlq(ConsumerRecord<String, Object> record) {
        log.error("DLQ message received | topic={} | key={} | offset={} | value={}",
            record.topic(),
            record.key(),
            record.offset(),
            record.value()
        );
        // TODO: persist to dead_letter_jobs table and alert via Slack/email
    }
}
