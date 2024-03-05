package com.example.resttemplate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Let's also take a look how we can test web layer
 * without using MockMvc as client.
 */
@WebMvcTest
public class EEE_AnotherRequestFactory {

    @SpringBootApplication
    static class TheSpringBootApplication {

        record ResponseModel(String name, int age) {
        }

        @RestController
        static class TheRestController {
            @GetMapping("/api/v1/some-endpoint/{name}/{age}")
            public ResponseModel getResponse() {
                return new ResponseModel("John", 25);
            }

        }

        @Bean
        RestOperations mockMvcRestOperations(MockMvc mockMvc) {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.setRequestFactory(new MockMvcClientHttpRequestFactory(mockMvc));
            return restTemplate;
        }
    }

    @Autowired
    RestOperations restOperations;

    @Test
    void callOurGetEndpoint_shouldReturnOurResponse() {

        record ClientSideResponse(String name, int age) {
        }

        var response = restOperations.getForObject(
                "/api/v1/some-endpoint/{name}/{age}",
                ClientSideResponse.class,
                "John", 25
        );
        assertThat(response.name()).isEqualTo("John");
        assertThat(response.age()).isEqualTo(25);

        // the rest template can also be tweaked not to throw exceptions
        // when 4xx or 5xx status codes are returned

        var restTemplate = (RestTemplate) restOperations;

        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            protected void handleError(ClientHttpResponse response, HttpStatusCode statusCode) {
                // we won't throw exceptions here, and instead we will let the flow handle the response status
            }
        });

        // now we can make a request to a non-existing endpoint
        var fourOhFour = restOperations.getForEntity(
                "/api/v1/another-endpoint/{name}/{age}",
                ClientSideResponse.class,
                "John", 25
        );
        assertThat(fourOhFour.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fourOhFour.getBody()).isNull();
    }

}
