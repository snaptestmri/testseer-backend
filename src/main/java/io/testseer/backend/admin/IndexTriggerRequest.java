package io.testseer.backend.admin;

public record IndexTriggerRequest(
        String commitSha   // optional — null means resolve HEAD
) {}
