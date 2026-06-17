package io.testseer.backend.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class JobNotReplayableException extends RuntimeException {
    public JobNotReplayableException(String jobId, String status) {
        super("Job " + jobId + " is not replayable from status " + status);
    }
}
