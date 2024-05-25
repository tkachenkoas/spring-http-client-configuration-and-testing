package com.example.resttemplate;

import com.example.resttemplate.Part03_02_ClientHttpRequestFactory.TheSpringBootApplication.MockMvcRestTemplate;
import com.example.resttemplate.Part03_02_ClientHttpRequestFactory.TheSpringBootApplication.NonThrowingMockMvcRestTemplate;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockMvcClientHttpRequestFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Illustrates how to use {@link MockMvcClientHttpRequestFactory} to test the client-side
 * with RestTemplate, and not MockMvc.
 */
@WebMvcTest
public class Part03_02_ClientHttpRequestFactory {

    // this is the "inlined" application we're going to test
    @SpringBootApplication
    static class TheSpringBootApplication {

        record ResponseModel(String name, int age) {
        }

        // this is our server-side controller
        @RestController
        static class TheRestController {
            @GetMapping("/api/v1/some-endpoint/{name}/{age}")
            public ResponseModel getResponse(@PathVariable Integer age, @PathVariable String name) {
                return new ResponseModel(name, age);
            }

        }

        @Qualifier("mockMvcRestOperations")
        @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
        @Retention(RetentionPolicy.RUNTIME)
        @interface MockMvcRestTemplate {
        }

        @MockMvcRestTemplate
        @Bean
        RestOperations mockMvcRestOperations(MockMvc mockMvc) {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.setRequestFactory(new MockMvcClientHttpRequestFactory(mockMvc));
            return restTemplate;
        }

        // by default, RestTemplate throws exceptions when 4xx or 5xx status codes are returned
        // but when we know that we expect such status codes, we can tweak the RestTemplate
        // to not throw exceptions, so that to assert the response status ourselves
        @Qualifier("nonThrowingRestOperations")
        @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
        @Retention(RetentionPolicy.RUNTIME)
        @interface NonThrowingMockMvcRestTemplate {
        }

        @NonThrowingMockMvcRestTemplate
        @Bean
        RestOperations nonThrowingRestOperations(MockMvc mockMvc) {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.setRequestFactory(new MockMvcClientHttpRequestFactory(mockMvc));
            restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
                @Override
                protected void handleError(@NotNull ClientHttpResponse response, @NotNull HttpStatusCode statusCode) {
                    // we won't throw exceptions here, and instead we will let the flow handle the response status
                }
            });
            return restTemplate;
        }
    }

    @MockMvcRestTemplate
    @Autowired
    RestOperations restOperations;

    @NonThrowingMockMvcRestTemplate
    @Autowired
    RestOperations nonThrowingRestOperations;

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

        assertThatExceptionOfType(HttpClientErrorException.NotFound.class)
                .isThrownBy(() -> restOperations.getForObject("/api/v1/non-existing-endpoint", ClientSideResponse.class));

        // now we can make a request to a non-existing endpoint without causing an exception
        var fourOhFour = nonThrowingRestOperations.getForEntity(
                "/api/v1/non-existing-endpoint", String.class
        );
        assertThat(fourOhFour.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fourOhFour.getBody()).isNull();
    }

}
