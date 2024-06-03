package com.example.resttemplate;

import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.mockserver.client.MockServerClient;
import org.mockserver.junit.jupiter.MockServerSettings;

@MockServerSettings(ports = 1090)
@RequiredArgsConstructor
public class TestWithMockServer {

    protected final MockServerClient mockServer;

    protected record SampleResponseModel(String name, int age) {
    }

    protected record ErrorResponseModel(String error) {
    }

    @BeforeEach
    void reset() {
        mockServer.reset();
        // expect GET /some-endpoint
        // and respond with 200 OK + response body
        mockServer.when(
                org.mockserver.model.HttpRequest.request()
                        .withMethod("GET")
                        .withPath("/some-endpoint")
        ).respond(
                org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"John\",\"age\":25}")
        );
        // + configure non-ok responses
        mockServer.when(
                org.mockserver.model.HttpRequest.request()
                        .withMethod("GET")
                        .withPath("/bad-req-endpoint")
        ).respond(
                org.mockserver.model.HttpResponse.response()
                        .withStatusCode(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Bad response for bad request\"}")
        );
    }

}
