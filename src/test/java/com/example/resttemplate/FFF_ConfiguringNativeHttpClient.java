package com.example.resttemplate;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.PropertyResolver;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StopWatch;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static org.apache.hc.core5.pool.PoolConcurrencyPolicy.LAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

@MockServerSettings(ports = 1100)
@RequiredArgsConstructor
@Slf4j
public class FFF_ConfiguringNativeHttpClient {

    private final MockServerClient mockServer;

    @BeforeEach
    void reset() {
        mockServer.reset();
    }

    @Test
    void settingTimeout_isAGoodIdea() {
        // configure rest template to have a timeout
        RestTemplate restTemplate = new RestTemplate();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(2));
        restTemplate.setRequestFactory(factory);

        // remote server is not responding in reasonable time
        mockServer.when(
                request()
                        .withMethod("GET")
                        .withPath("/some-endpoint")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withDelay(
                                TimeUnit.SECONDS,
                                30 // 3000? :)
                        )
        );


        // and we'd rather not wait for all the time in the universe for the response

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        assertThatThrownBy(
                () -> restTemplate.getForObject("http://localhost:1100/some-endpoint", String.class)
        ).isInstanceOf(ResourceAccessException.class)
                .cause()
                .isInstanceOf(SocketTimeoutException.class);

        stopWatch.stop();
        var elapsed = stopWatch.getTotalTimeMillis();
        assertThat(elapsed)
                .isLessThan(2_300)
                .isGreaterThan(2_000);
    }

    @Test
    void connectionsShouldBePooled_andThePoolSizeShouldBeMonitored() {
        RestTemplate restTemplate = new RestTemplate();

        // pooling and reusing connections is a good pattern when we are making multiple requests
        // to the same server - it saves time and resources on both client and server side
        // because we don't have to establish a new connection for each request
        // and waste all the time on handshaking and other https steps
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(10);
        connectionManager.setDefaultMaxPerRoute(10);

        // in production, you will probably use a real meter registry like
        // PrometheusMeterRegistry or DropwizardMeterRegistry
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, "our-http-client")
                .bindTo(meterRegistry);

        restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .build()
        ));

        // remote server is not responding in reasonable time
        mockServer.when(
                request()
                        .withMethod("GET")
                        .withPath("/some-endpoint")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withDelay(
                                TimeUnit.SECONDS, 2
                        )
        );

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // now let's make 20 concurrent requests and wait for all of them to finish

        CompletableFuture<Void> future = CompletableFuture.allOf(IntStream.range(0, 20)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    restTemplate.getForObject(
                            "http://localhost:1100/some-endpoint",
                            String.class
                    );
                }))
                .toArray(CompletableFuture[]::new)
        );
        await().atMost(Duration.ofSeconds(1))
                .until(() -> connectionManager.getTotalStats().getLeased() == 10);
        String metersAsString = meterRegistry.getMetersAsString();
        /**
         * httpcomponents.httpclient.pool.route.max.default(GAUGE)[httpclient='our-http-client']; value=10.0
         * httpcomponents.httpclient.pool.total.connections(GAUGE)[httpclient='our-http-client', state='available']; value=0.0
         * httpcomponents.httpclient.pool.total.connections(GAUGE)[httpclient='our-http-client', state='leased']; value=10.0
         * httpcomponents.httpclient.pool.total.max(GAUGE)[httpclient='our-http-client']; value=10.0
         * httpcomponents.httpclient.pool.total.pending(GAUGE)[httpclient='our-http-client']; value=1.0
         */
        assertThat(metersAsString).contains(
                "httpcomponents.httpclient.pool.total.connections(GAUGE)[httpclient='our-http-client', state='leased']; value=10.0"
        );
        future.join();

        stopWatch.stop();
        var elapsed = stopWatch.getTotalTimeMillis();
        // this is because we are making 20 requests concurrently
        // but we have only 10 connections in the pool
        // so the second half of the requests will have to wait
        // and if you want bigger throughput you should increase the pool size
        assertThat(elapsed).isGreaterThan(4_000).isLessThan(4_500);
    }

    @Test
    void requestDuration_shouldBeLimited() {
        // configure rest template with a limit of connection request time
        RestTemplate restTemplate = new RestTemplate();
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                                // Determines the timeout until a new connection is fully established.
                                // A timeout value of zero is interpreted as an infinite timeout.
                                // Default: 3 minutes
                                .setConnectTimeout(Timeout.of(2, TimeUnit.SECONDS))
                                // Determines the default socket timeout value for I/O operations.
                                // Default: null (undefined)
                                // Returns:
                                // the default socket timeout value for I/O operations.
                                .setSocketTimeout(Timeout.of(6, TimeUnit.SECONDS))
                                .build()
                ).build();
        restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        // Returns the connection lease request timeout used when requesting a
                                        // connection from the connection manager.
                                        // Default: 3 minutes.
                                        .setConnectionRequestTimeout(Timeout.of(2, TimeUnit.SECONDS))
                                        // Determines the timeout until arrival of a response from the opposite endpoint.
                                        // A timeout value of zero is interpreted as an infinite timeout.
                                        // Please note that response timeout may be unsupported by HTTP transports with message multiplexing.
                                        // Default: null
                                        .setResponseTimeout(Timeout.of(6, TimeUnit.SECONDS))
                                        .build()
                        )
                        .setConnectionManager(manager)
                        .build()
        ));

        // remote server is not responding in reasonable time
        mockServer.when(request().withMethod("GET").withPath("/some-endpoint"))
                .respond(response().withStatusCode(200).withDelay(TimeUnit.SECONDS, 5));

        CompletableFuture.runAsync(() -> restTemplate.getForObject("http://localhost:1100/some-endpoint", String.class));

        await()
                .atMost(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    PoolStats totalStats = manager.getTotalStats();
                    assertThat(totalStats.getPending()).isEqualTo(0);
                    assertThat(totalStats.getLeased()).isEqualTo(1);
                    assertThat(totalStats.getAvailable()).isEqualTo(0);
                    assertThat(totalStats.getMax()).isEqualTo(1);
                });

        assertThatThrownBy(() -> restTemplate.getForObject("http://localhost:1100/some-endpoint", String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(ConnectionRequestTimeoutException.class);
    }

    @Test
    void whenCallingMultipleHostsWithSameRestTemplate_connectionsShouldBePooledPerRoute() {
        // configure rest template with a limit of connections per route

        RestTemplate restTemplate = new RestTemplate();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnPerRoute(5)
                .setMaxConnTotal(10)
                // Higher concurrency but with lax connection max limit guarantees.
                // LAX,
                // Strict connection max limit guarantees.
                // STRICT
                .setPoolConcurrencyPolicy(LAX)
                .build();

        restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .build()
        ));

        // this remote server is slow
        ClientAndServer slowServer = ClientAndServer.startClientAndServer(1200);
        slowServer.when(
                request()
                        .withMethod("GET")
                        .withPath("/slow-endpoint")
        ).respond(
                response().withStatusCode(200).withDelay(TimeUnit.SECONDS, 5)
        );

        // and this one is faster
        mockServer.when(
                request().withMethod("GET").withPath("/fast-endpoint")
        ).respond(
                response().withStatusCode(200).withDelay(TimeUnit.SECONDS, 1)
        );

        // now we will use same rest template to make requests to both servers
        // and count how many requests are made to each server

        AtomicInteger slowServerRequests = new AtomicInteger();
        AtomicInteger fastServerRequests = new AtomicInteger();

        // make 30 concurrent request attempts to fast server
        for (int i = 0; i < 30; i++) {
            CompletableFuture.runAsync(() -> {
                restTemplate.getForObject("http://localhost:1100/fast-endpoint", String.class);
                log.info("fastServerRequests = " + fastServerRequests.incrementAndGet());
            });
        }

        // and also 10 requests to slow server
        for (int i = 0; i < 10; i++) {
            CompletableFuture.runAsync(() -> {
                restTemplate.getForObject("http://localhost:1200/slow-endpoint", String.class);
                log.info("slowServerRequests = " + slowServerRequests.incrementAndGet());
            });
        }

        // fast server should have 30 requests in ~10-12 seconds
        await().atMost(Duration.ofSeconds(12))
                .until(() -> fastServerRequests.get() == 30);

        // wait till slow server is done
        await().atMost(Duration.ofSeconds(10))
                .until(() -> slowServerRequests.get() == 10);
    }

    public RestTemplateBuilder builderWithSaneAmountOfConfigs(
            String clientName,
            MeterRegistry meterRegistry,
            PropertyResolver propertyResolver
    ) {
        String propertyPrefix = "http.client." + clientName;
        BiFunction<String, Integer, Integer> getIntProperty = (key, defaultValue) -> propertyResolver.getProperty(
                propertyPrefix + "." + key, Integer.class, defaultValue
        );
        return new RestTemplateBuilder()
                .requestFactory(() -> {
                    PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                            .setMaxConnPerRoute(getIntProperty.apply("max-conn-per-route", 5))
                            .setMaxConnTotal(getIntProperty.apply("max-conn-total", 10))
                            .setPoolConcurrencyPolicy(LAX)
                            .setDefaultConnectionConfig(
                                    ConnectionConfig.custom()
                                            .setConnectTimeout(Timeout.ofSeconds(
                                                    getIntProperty.apply("connect-timeout-seconds", 3)
                                            ))
                                            .setSocketTimeout(Timeout.ofSeconds(
                                                    getIntProperty.apply("socket-timeout-seconds", 60)
                                            ))
                                            .build()
                            )
                            .build();
                    new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, clientName);
                    return new HttpComponentsClientHttpRequestFactory(
                            HttpClients.custom()
                                    .setConnectionManager(connectionManager)
                                    .setDefaultRequestConfig(
                                            RequestConfig.custom()
                                                    .setConnectionRequestTimeout(
                                                            Timeout.ofSeconds(
                                                                    getIntProperty.apply("connection-request-timeout-seconds", 3)
                                                            )
                                                    )
                                                    .setResponseTimeout(
                                                            Timeout.ofSeconds(
                                                                    getIntProperty.apply("response-timeout-seconds", 60)
                                                            )
                                                    )
                                                    .build()
                                    )
                                    .build()
                    );
                });
    }

}
