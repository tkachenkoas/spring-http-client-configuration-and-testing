package com.example.resttemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.Delay;
import org.mockserver.model.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@SpringBootTest
@MockServerSettings(ports = 8090)
@RequiredArgsConstructor
@Slf4j
public class Part06_ObservabilityAndMonitoring {

    private final ClientAndServer mockServer;

    /**
     * The excludes of auto-configurations are just to start the context,
     * since the repository has various spring-boot-starters for other examples.
     * They are not related to the content of this class.
     */
    @SpringBootApplication(
            exclude = {
                    MongoAutoConfiguration.class, MongoDataAutoConfiguration.class,
                    RedisAutoConfiguration.class, JdbcRepositoriesAutoConfiguration.class,
                    DataSourceAutoConfiguration.class
            }
    )
    static class SimpleTestApplicationForRestTemplateMonitoring {

    }

    @Autowired
    RestTemplateBuilder restTemplateBuilder;

    /**
     * Unless prometheus / dropwizard / other monitoring system is configured,
     * the default registry is SimpleMeterRegistry.
     */
    @Autowired
    SimpleMeterRegistry meterRegistry;

    @Test
    void restClient_builtViaContextBuilder_willHaveMonitoring() {
        RestTemplate restTemplate = restTemplateBuilder.build();

        mockServer.when(request().withMethod("GET")
                        .withPath("/api/v1/employees/1"))
                .respond(response().withStatusCode(200));

        restTemplate.getForObject("http://localhost:8090/api/v1/employees/{id}", String.class, 1);

        // and now let's also make one long task
        mockServer.when(request().withMethod("GET")
                        .withPath("/api/v1/employees/2"))
                .respond(response().withStatusCode(200).withDelay(Delay.seconds(5)));

        CompletableFuture.runAsync(
                () -> restTemplate.getForObject("http://localhost:8090/api/v1/employees/{id}", String.class, 2)
        );

        /**
         * http.client.requests(TIMER)[client.name='localhost', error='none', exception='none', method='GET', outcome='SUCCESS',
         * status='200', uri='/api/v1/employees/{id}']; count=1.0, total_time=0.1194284 seconds, max=0.1194284 seconds
         * http.client.requests.active(LONG_TASK_TIMER)[client.name='localhost', exception='none', method='GET', outcome='UNKNOWN',
         * status='CLIENT_ERROR', uri='/api/v1/employees/{id}']; active_tasks=1.0, duration=0.0085765 seconds
         */
        List<String> relatedMetrics = Arrays.stream(meterRegistry.getMetersAsString().split("\n"))
                .filter(name -> name.startsWith("http.client.requests"))
                .toList();

        // metrics contain information about executed requests: count, total_time, max
        // for each uri templated, target host, method, status
        assertThat(relatedMetrics)
                .anyMatch(metric -> metric.contains("http.client.requests(TIMER)") &&
                        metric.contains("outcome='SUCCESS'") &&
                        metric.contains("status='200'") &&
                        metric.contains("count=1.0") &&
                        metric.contains("total_time=") &&
                        metric.contains("max=") &&
                        metric.contains("client.name='localhost'") &&
                        metric.contains("uri='/api/v1/employees/{id}'")
                );

        // metrics will also contain information about active requests
        assertThat(relatedMetrics)
                .anyMatch(metric -> metric.contains("http.client.requests.active(LONG_TASK_TIMER)") &&
                        metric.contains("outcome='UNKNOWN'") &&
                        metric.contains("status='CLIENT_ERROR'") &&
                        metric.contains("active_tasks=1.0") &&
                        metric.contains("uri='/api/v1/employees/{id}'"));
    }

    @Test
    @SneakyThrows
    void misusingUriTemplate_canProduceTooManyMetrics() {
        RestTemplate restTemplate = restTemplateBuilder.build();

        for (Integer i : List.of(1, 2, 3)) {
            mockServer.when(request()
                            .withMethod("GET")
                            .withPath("/api/v1/employees/" + i))
                    .respond(response()
                            .withStatusCode(200)
                            .withContentType(MediaType.APPLICATION_JSON)
                            .withBody(new ObjectMapper().writeValueAsString(
                                    Map.of("id", i, "name", "John Doe " + i))
                            )
                    );

            restTemplate.getForObject("http://localhost:8090/api/v1/employees/" + i, String.class);
        }

        List<String> relatedMetrics = Arrays.stream(meterRegistry.getMetersAsString().split("\n"))
                .filter(name -> name.startsWith("http.client.requests"))
                .toList();

        /**
         * http.client.requests(TIMER)[client.name='localhost', error='none', exception='none', method='GET', outcome='SUCCESS',
         * status='200', uri='/api/v1/employees/1']; count=1.0, total_time=0.1304859 seconds, max=0.1304859 seconds
         * http.client.requests(TIMER)[client.name='localhost', error='none', exception='none', method='GET', outcome='SUCCESS',
         * status='200', uri='/api/v1/employees/3']; count=1.0, total_time=0.0031198 seconds, max=0.0031198 seconds
         * http.client.requests(TIMER)[client.name='localhost', error='none', exception='none', method='GET', outcome='SUCCESS',
         * status='200', uri='/api/v1/employees/2']; count=1.0, total_time=0.0055739 seconds, max=0.0055739 seconds
         * http.client.requests.active(LONG_TASK_TIMER)[client.name='localhost', exception='none', method='GET', outcome='UNKNOWN',
         * status='CLIENT_ERROR', uri='/api/v1/employees/1']; active_tasks=0.0, duration=0.0 seconds
         * http.client.requests.active(LONG_TASK_TIMER)[client.name='localhost', exception='none', method='GET', outcome='UNKNOWN',
         * status='CLIENT_ERROR', uri='/api/v1/employees/2']; active_tasks=0.0, duration=0.0 seconds
         * http.client.requests.active(LONG_TASK_TIMER)[client.name='localhost', exception='none', method='GET', outcome='UNKNOWN',
         * status='CLIENT_ERROR', uri='/api/v1/employees/3']; active_tasks=0.0, duration=0.0 seconds
         * http.client.requests.active(LONG_TASK_TIMER)[client.name='localhost', exception='none', method='GET', outcome='UNKNOWN',
         */
        assertThat(relatedMetrics)
                .hasSize(6);

        assertThat(relatedMetrics)
                .anyMatch(metric -> metric.contains("uri='/api/v1/employees/1'"))
                .anyMatch(metric -> metric.contains("uri='/api/v1/employees/2'"))
                .anyMatch(metric -> metric.contains("uri='/api/v1/employees/3'"));
    }

    @Test
    @SneakyThrows
    void connectionPool_canBeEasilyMonitored() {
        RestTemplate restTemplate = new RestTemplate();

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(10);
        restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom().setConnectionManager(connectionManager).build()
        ));

        // micrometer has a built-in binder for connection pool monitoring
        new PoolingHttpClientConnectionManagerMetricsBinder(
                connectionManager, "our-pool"
        ).bindTo(meterRegistry);

        // now let's block a few connections in the pool with a long task

        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/api/v1/employees/1"))
                .respond(response()
                        .withStatusCode(200)
                        .withContentType(MediaType.APPLICATION_JSON)
                        .withBody("{\"id\": 1, \"name\": \"John Doe\"}")
                        .withDelay(Delay.seconds(5))
                );


        for (int i = 0; i < 3; i++) {
            CompletableFuture.runAsync(
                    () -> restTemplate.getForObject("http://localhost:8090/api/v1/employees/{id}", String.class, 1)
            );
        }
        Thread.sleep(1000);

        String metersAsString = meterRegistry.getMetersAsString();
        /**
         * httpcomponents.httpclient.pool.route.max.default(GAUGE)[httpclient='our-pool']; value=5.0
         * httpcomponents.httpclient.pool.total.connections(GAUGE)[httpclient='our-pool', state='leased']; value=3.0
         * httpcomponents.httpclient.pool.total.connections(GAUGE)[httpclient='our-pool', state='available']; value=0.0
         * httpcomponents.httpclient.pool.total.max(GAUGE)[httpclient='our-pool']; value=10.0
         * httpcomponents.httpclient.pool.total.pending(GAUGE)[httpclient='our-pool']; value=0.0
         */
        List<String> poolMetrics = Arrays.stream(metersAsString.split("\n"))
                .filter(name -> name.contains("our-pool"))
                .toList();

        assertThat(poolMetrics).hasSize(5);
        assertThat(poolMetrics)
                .anyMatch(metric -> metric.contains("httpcomponents.httpclient.pool.total.connections(GAUGE)") &&
                        metric.contains("state='leased'") &&
                        metric.contains("value=3.0"))
                .anyMatch(metric -> metric.contains("httpcomponents.httpclient.pool.total.max(GAUGE)"));
    }

}
