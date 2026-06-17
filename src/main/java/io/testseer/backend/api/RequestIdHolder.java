package io.testseer.backend.api;

import io.testseer.backend.observability.MdcKeys;
import org.slf4j.MDC;

public final class RequestIdHolder {

    private RequestIdHolder() {}

    public static String current() {
        String requestId = MDC.get(MdcKeys.REQUEST_ID);
        return requestId != null && !requestId.isBlank() ? requestId : "unknown";
    }
}
