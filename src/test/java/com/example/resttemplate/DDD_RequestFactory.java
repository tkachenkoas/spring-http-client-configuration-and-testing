package com.example.resttemplate;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate can be considered an example of "facade" design pattern.
 * E.g. it hides the complexity of making HTTP requests and provides a simple API to the user,
 * while also maintaining the flexibility to customize the requests and actions around
 * responses.
 */
public class DDD_RequestFactory {

    @Test
    void aGlimpseIntoMockRestServiceServer() {
        RestTemplate restTemplate = new RestTemplate();

        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);

        Assertions.assertThat(restTemplate.getRequestFactory().getClass().getSimpleName())
                /**
                 * @{@link org.springframework.test.web.client.MockRestServiceServer#MockClientHttpRequestFactory}
                 * <p>
                 * https://i.imgflip.com/8gm1t9.jpg
                 * https://i.imgflip.com/8gm2io.jpg
                 * <p>
                 * // no actual HTTP request is made, so we do whatever we want
                 * with expected "requests" and "responses"
                 */
                .isEqualTo("MockClientHttpRequestFactory");
    }

    @Test
    void theDefaultRequestFactoryOfRestTemplateBehavior() {
        RestTemplate restTemplate = new RestTemplate();

        Assertions.assertThat(restTemplate.getRequestFactory())
                .isInstanceOf(org.springframework.http.client.SimpleClientHttpRequestFactory.class);

        // try and guess what will happen here
        restTemplate.patchForObject("https://api.example.com/v1/some-endpoint", null, String.class);
    }

}
