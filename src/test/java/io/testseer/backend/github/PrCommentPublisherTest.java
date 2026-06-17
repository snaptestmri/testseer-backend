package io.testseer.backend.github;

import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PrCommentPublisherTest {

    @Test
    void publishOrUpdate_createsCommentWhenNoneExists() {
        var getResponse = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        var getUri = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        var postResponse = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        var postUri = mock(RestClient.RequestBodyUriSpec.class, RETURNS_DEEP_STUBS);
        var restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(getUri);
        when(getUri.uri(eq("/repos/{owner}/{repo}/issues/{issue}/comments"), eq("acme"), eq("orders"), eq(47)))
                .thenReturn(getUri);
        when(getUri.retrieve()).thenReturn(getResponse);
        when(getResponse.body(any(ParameterizedTypeReference.class))).thenReturn(List.of());

        when(restClient.post()).thenReturn(postUri);
        when(postUri.uri(eq("/repos/{owner}/{repo}/issues/{issue}/comments"), eq("acme"), eq("orders"), eq(47)))
                .thenReturn(postUri);
        when(postUri.body(any(Map.class))).thenReturn(postUri);
        when(postUri.retrieve()).thenReturn(postResponse);

        PrCommentPublisher publisher = new PrCommentPublisher(restClient, "token", true);
        publisher.publishOrUpdate("acme", "orders", 47, PrCommentFormatter.MARKER + "\nbody");

        verify(postUri).body(Map.of("body", PrCommentFormatter.MARKER + "\nbody"));
        verify(restClient, never()).patch();
    }

    @Test
    void publishOrUpdate_updatesExistingComment() {
        var getResponse = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        var getUri = mock(RestClient.RequestHeadersUriSpec.class, RETURNS_DEEP_STUBS);
        var patchResponse = mock(RestClient.ResponseSpec.class, RETURNS_DEEP_STUBS);
        var patchUri = mock(RestClient.RequestBodyUriSpec.class, RETURNS_DEEP_STUBS);
        var restClient = mock(RestClient.class);

        when(restClient.get()).thenReturn(getUri);
        when(getUri.uri(anyString(), any(), any(), any())).thenReturn(getUri);
        when(getUri.retrieve()).thenReturn(getResponse);
        when(getResponse.body(any(ParameterizedTypeReference.class))).thenReturn(List.of(
                Map.of("id", 99L, "body", PrCommentFormatter.MARKER + "\nold")));

        when(restClient.patch()).thenReturn(patchUri);
        when(patchUri.uri(eq("/repos/{owner}/{repo}/issues/comments/{commentId}"), eq("acme"), eq("orders"), eq(99L)))
                .thenReturn(patchUri);
        when(patchUri.body(any(Map.class))).thenReturn(patchUri);
        when(patchUri.retrieve()).thenReturn(patchResponse);

        PrCommentPublisher publisher = new PrCommentPublisher(restClient, "token", true);
        publisher.publishOrUpdate("acme", "orders", 47, PrCommentFormatter.MARKER + "\nnew");

        verify(patchUri).body(Map.of("body", PrCommentFormatter.MARKER + "\nnew"));
        verify(restClient, never()).post();
    }

    @Test
    void publishOrUpdate_noOpWhenDisabled() {
        RestClient restClient = mock(RestClient.class);
        PrCommentPublisher publisher = new PrCommentPublisher(restClient, "", false);

        publisher.publishOrUpdate("acme", "orders", 47, "body");

        verifyNoInteractions(restClient);
    }
}
