package io.testseer.backend.observability;

import io.testseer.backend.webhook.IngestionJob;
import org.slf4j.MDC;

import java.util.Map;

public final class MdcContext {

    private MdcContext() {}

    public static void put(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    public static void putJob(IngestionJob job) {
        put(MdcKeys.JOB_ID, job.jobId());
        put(MdcKeys.SERVICE_ID, job.serviceId());
        put(MdcKeys.ORG_ID, job.orgId());
        put(MdcKeys.REPO, job.repo());
        put(MdcKeys.JOB_TYPE, job.jobType());
    }

    public static void runWithJob(IngestionJob job, Runnable action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            putJob(job);
            action.run();
        } finally {
            restore(previous);
        }
    }

    public static void clear() {
        MDC.clear();
    }

    public static void remove(String key) {
        MDC.remove(key);
    }

    private static void restore(Map<String, String> previous) {
        MDC.clear();
        if (previous != null) {
            MDC.setContextMap(previous);
        }
    }
}
