package com.example.resttemplate;


import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.util.Timeout;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.*;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StopWatch;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Calls to our application need to come via http, so we need to start a server.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Slf4j
public class GGG_WhenThingsGoNotAsPlanned {

    /**
     * The excludes of auto-configurations are just to start the context,
     * since the repository has various spring-boot-starters for other examples.
     * They are not related to the content of this class.
     */
    @SpringBootApplication(
            exclude = {
                    MongoAutoConfiguration.class,
                    MongoDataAutoConfiguration.class,
                    RedisAutoConfiguration.class,
                    JdbcRepositoriesAutoConfiguration.class,
                    DataSourceAutoConfiguration.class
            }
    )
    static class NetworkCasesEmulatingController {

        @RestController
        static class TheTestController {

            List<String> slices = List.of(
                    "[{",
                    "  \"name\": \"First\"",
                    "},",
                    "{",
                    "  \"name\": \"Second\"",
                    "},",
                    "{",
                    "  \"name\": \"Third\"",
                    "}]"
            );

            // delay and pause allow us to simulate slow and sliced response
            @GetMapping("/sliced-endpoint")
            public void returnSlicedResponseAccordingToRequest(
                    @RequestParam(required = false, defaultValue = "0") Long delay,
                    @RequestParam(required = false, defaultValue = "0") Long pause,
                    HttpServletResponse response
            ) throws Exception {
                Thread.sleep(delay);
                response.setContentType("application/json");
                PrintWriter writer = response.getWriter();
                for (String slice : slices) {
                    Thread.sleep(pause);
                    log.debug("Sending: {}", slice);
                    writer.println(slice);
                    writer.flush();
                }
            }

            @GetMapping
            public String returnGivenAmountOfData(
                    @RequestParam Long kilobytes
            ) {
                return StringUtils.repeat("a", (int) (kilobytes * 1024));
            }

        }
    }

    @LocalServerPort
    int serverPort;

    /**
     * see {@link org.apache.hc.client5.http.impl.classic.InternalExecRuntime#execute(String, ClassicHttpRequest, HttpClientContext)}
     */
    @Test
    void clientResponseTimeout_overridesConnPoolManagerSocketTimeout() throws Exception {
        var socketLessThanResponse = buildRestTemplateWithLimits(
                // socket timeout
                Timeout.ofMilliseconds(200),
                // response timeout
                Timeout.ofMilliseconds(500)
        );

        String response = socketLessThanResponse.getForObject(
                "http://localhost:{port}/sliced-endpoint?delay=300", String.class, serverPort
        );
        JSONAssert.assertEquals(
                "[{\"name\":\"First\"},{\"name\":\"Second\"},{\"name\":\"Third\"}]",
                response, true
        );

        assertThatThrownBy(() -> {
            socketLessThanResponse.getForObject(
                    "http://localhost:{port}/sliced-endpoint?delay=600", String.class, serverPort
            );
        }).isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class)
                .hasMessageContaining("Read timed out");


        var responseLessThanSocket = buildRestTemplateWithLimits(
                // socket timeout
                Timeout.ofMilliseconds(500),
                // response timeout
                Timeout.ofMilliseconds(200)
        );

        // even though we set socket timeout to 500ms, the actual limit comes from response timeout
        assertThatThrownBy(() -> {
            responseLessThanSocket.getForObject(
                    "http://localhost:{port}/sliced-endpoint?pause=300", String.class, serverPort
            );
        }).isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class)
                .hasMessageContaining("Read timed out");
    }

    @Test
    void timeoutsOnRestTemplateMayNotBeEnough() throws Exception {
        var restTemplateWithTimeout = buildRestTemplateWithLimits();

        // now let's see what happens when we set both
        // initial delay and inter-line delay to 900ms
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        // making such request will succeed, and we'll get the response
        var responseEntity = restTemplateWithTimeout.getForObject(
                "http://localhost:{port}/sliced-endpoint?pause=900&delay=900", String.class,
                serverPort
        );

        JSONAssert.assertEquals(
                "[{\"name\":\"First\"},{\"name\":\"Second\"},{\"name\":\"Third\"}]",
                responseEntity, true
        );
        stopWatch.stop();

        // as long as we kept receiving data with intervals less than the timeout,
        // overall time can be much longer than the timeout and then you expect
        double time = stopWatch.getTotalTime(TimeUnit.SECONDS);
        assertThat(time).isBetween(7.5, 9.0);
    }

    ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withLogConsumer(new Slf4jLogConsumer(log));

    record ToxiProxySetup(
            String host,
            int port,
            Proxy proxy
    ) {

    }

    /**
     * See documentation of TestContainers for more details on how to configure
     * networks in various cases.
     */
    private ToxiProxySetup setupToxiProxy() throws IOException {
        Testcontainers.exposeHostPorts(serverPort);
        toxiproxy.start();

        log.info("Server port: {}", serverPort);
        ToxiproxyClient toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        Proxy proxy = toxiproxyClient.createProxy(
                "proxy-to-local-server", "0.0.0.0:8666",
                "host.testcontainers.internal:" + serverPort
        );
        String proxyHost = toxiproxy.getHost();
        int proxyPort = toxiproxy.getMappedPort(8666);
        log.info("Toxiproxy port: {} & IP: {}", proxyPort, proxyHost);

        return new ToxiProxySetup(proxyHost, proxyPort, proxy);
    }

    @Test
    void betterWayOfMockingSlicedBehaviorIsUsingToxiProxySlicer() throws Exception {
        ToxiProxySetup setup = setupToxiProxy();

        setup.proxy().toxics().slicer(
                "slice", ToxicDirection.DOWNSTREAM,
                // size in bytes of the slice
                10,
                // delay between slices in microseconds
                90000
        );

        var restTemplateWithTimeout = buildRestTemplateWithLimits();

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        // now server will respond quickly, but the client will receive the response
        // in slices with 900ms delay between them
        var responseEntity = restTemplateWithTimeout
                .getForObject("http://{ip}:{port}/sliced-endpoint", String.class,
                        setup.host(), setup.port()
                );
        JSONAssert.assertEquals(
                "[{\"name\":\"First\"},{\"name\":\"Second\"},{\"name\":\"Third\"}]",
                responseEntity, true
        );
        stopWatch.stop();

        // and overall time will be way about timeouts that we set
        double time = stopWatch.getTotalTime(TimeUnit.SECONDS);
        assertThat(time).isBetween(4.0, 5.0);
    }

    @Test
    void somethingCanHappenOnNetworkLevelThatWillCauseResponseToBeReturnedForever() throws Exception {
        ToxiProxySetup setup = setupToxiProxy();

        setup.proxy().toxics().bandwidth("slow-down", ToxicDirection.DOWNSTREAM,
                // rate in kilobytes per second
                2);

        var restTemplateWithTimeout = buildRestTemplateWithLimits();
        // call the "fast" endpoint that returns 12kb of data
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        String response = restTemplateWithTimeout.getForObject(
                "http://{ip}:{port}?kilobytes=12", String.class,
                setup.host(), setup.port()
        );

        assertThat(response).hasSize(12 * 1024);
        stopWatch.stop();
        double time = stopWatch.getTotalTime(TimeUnit.SECONDS);
        assertThat(time).isBetween(6.0, 7.0);
    }

    @Test
    void settingHardLimit_viaAbortingNativeRequest() {
        var restTemplateWithTimeout = buildRestTemplateWithLimits();

        HttpComponentsClientHttpRequestFactory apacheClientFactory = (HttpComponentsClientHttpRequestFactory)
                restTemplateWithTimeout.getRequestFactory();

        ClientHttpRequestFactory factoryWithHardTimeout = (uri, httpMethod) -> {
            var request = apacheClientFactory.createRequest(uri, httpMethod);
            return requestWithHardTimeout(request, 3, TimeUnit.SECONDS);
        };
        restTemplateWithTimeout.setRequestFactory(factoryWithHardTimeout);

        // now let's see what happens when we set inter-line delay to 900ms
        assertThatExceptionOfType(RestClientException.class).isThrownBy(() -> {
                    restTemplateWithTimeout.getForObject("http://localhost:{port}/sliced-endpoint?delay=800",
                            String.class, serverPort);
                }).havingCause()
                .isInstanceOf(SocketException.class)
                .withMessage("Socket closed");
    }

    @SneakyThrows
    private static ClientHttpRequest requestWithHardTimeout(
            ClientHttpRequest request,
            int timeout, TimeUnit timeoutTimeUnit
    ) {
        AbstractClientHttpRequest clientRequest = (AbstractClientHttpRequest) request;
        // get field "httpRequest" from the request
        Field reqField = ReflectionUtils.findField(request.getClass(), "httpRequest");
        ReflectionUtils.makeAccessible(reqField);
        HttpUriRequestBase apacheHttpRequest = (HttpUriRequestBase) reqField.get(clientRequest);
        // schedule canceling the request after provided timeout
        log.info("Scheduling hard cancel in {} {}", timeout, timeoutTimeUnit);
        Executors.newSingleThreadScheduledExecutor()
                .schedule(() -> {
                    log.info("Hard cancelling the request");
                    apacheHttpRequest.abort();
                }, timeout, timeoutTimeUnit);
        return request;
    }

    @Test
    void whenSomethingGoesWrongConsistently_weShouldShortCircuitCalls() {
        // let's imagine that service consistently returns slow responses
        // via our "native" sliced endpoint

        RestTemplate restTemplate = buildRestTemplateWithLimits();
        restTemplate.getForObject(
                "http://localhost:{port}/sliced-endpoint?delay=400",
                String.class, serverPort
        );

        CircuitBreaker circuitBreaker = CircuitBreaker.of(
                "slow-service", CircuitBreakerConfig.custom()
                        .slowCallDurationThreshold(Duration.ofMillis(200))
                        .slowCallRateThreshold(90)
                        .minimumNumberOfCalls(4)
                        .slidingWindowSize(10)
                        .slidingWindowType(COUNT_BASED)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .enableAutomaticTransitionFromOpenToHalfOpen()
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .maxWaitDurationInHalfOpenState(Duration.ofSeconds(2))
                        .build()
        );
        circuitBreaker.getEventPublisher().onStateTransition(event -> {
            log.info("Circuit breaker state transition: {}", event);
        });

        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            log.info("Current state: {}", circuitBreaker.getState());
            try {
                return circuitBreaker.executeCheckedSupplier(() -> {
                    ClientHttpResponse executed = execution.execute(request, body);
                    log.info("Call was executed successfully");
                    return executed;
                });
            } catch (Throwable e) {
                // request interceptor's contract is to throw IOException
                throw e instanceof IOException
                        ? (IOException) e
                        : new IOException(e);
            }
        };
        restTemplate.getInterceptors().add(interceptor);

        // the behavior of rest template is now changed

        // first 3 calls will be slow, but they will succeed
        for (int i = 0; i < 4; i++) {
            assertThatCode(() -> restTemplate.getForObject(
                    "http://localhost:{port}/sliced-endpoint?delay=400",
                    String.class, serverPort
            )).doesNotThrowAnyException();
        }

        // but if we make 4th, 5th, 6th and 7th call - they will fail
        for (int i = 0; i < 4; i++) {
            assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(() -> {
                        restTemplate.getForObject(
                                "http://localhost:{port}/sliced-endpoint?delay=400",
                                String.class, serverPort
                        );
                    }).havingRootCause().isInstanceOf(CallNotPermittedException.class)
                    .withMessageContaining("CircuitBreaker 'slow-service' is OPEN");
        }

        // e.g. the circuit breaker is now in open state
        assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);

        // however, after some time (1 second according to the configuration)
        // the circuit breaker will allow to make calls again
        log.info("Waiting for the circuit breaker to switch to half-open state");
        await().atMost(Duration.ofMillis(1200))
                .until(() -> circuitBreaker.getState() == State.HALF_OPEN);

        assertThatCode(() -> restTemplate.getForObject(
                "http://localhost:{port}/sliced-endpoint?delay=400",
                String.class, serverPort
        )).doesNotThrowAnyException();

        // but our circuit breaker will be in half-open state
        // and it will allow only preconfigured number of calls "for free"
        // the service already recovered, it can still block some calls

        assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(() -> {
                    restTemplate.getForObject(
                            "http://localhost:{port}/sliced-endpoint",
                            String.class, serverPort
                    );
                }).havingRootCause().isInstanceOf(CallNotPermittedException.class)
                .withMessageContaining("CircuitBreaker 'slow-service' is OPEN");

        // now let's wait for the circuit breaker to switch to half-open state
        // again and make the call
        log.info("Waiting for the circuit breaker to switch to half-open state");

        await().atMost(Duration.ofMillis(1200))
                .until(() -> circuitBreaker.getState() == State.HALF_OPEN);

        for (int i = 0; i < 4; i++) {
            assertThatCode(() -> restTemplate.getForObject(
                    "http://localhost:{port}/sliced-endpoint",
                    String.class, serverPort
            )).doesNotThrowAnyException();
        }

        // and once service got back to normal, circuit breaker will be closed
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
    }

    private static RestTemplate buildRestTemplateWithLimits() {
        return buildRestTemplateWithLimits(
                Timeout.ofSeconds(1), Timeout.ofSeconds(2)
        );
    }

    private static RestTemplate buildRestTemplateWithLimits(
            Timeout socketTimeout, Timeout responseTimeout
    ) {
        return new RestTemplateBuilder()
                .requestFactory(() -> {
                    PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                            .setDefaultConnectionConfig(
                                    ConnectionConfig.custom()
                                            .setSocketTimeout(socketTimeout)
                                            .setConnectTimeout(Timeout.ofSeconds(1))
                                            .build()
                            )
                            .build();
                    return new HttpComponentsClientHttpRequestFactory(
                            HttpClients.custom()
                                    .setConnectionManager(connectionManager)
                                    .setDefaultRequestConfig(
                                            RequestConfig.custom()
                                                    .setConnectionRequestTimeout(Timeout.ofSeconds(1))
                                                    .setResponseTimeout(responseTimeout)
                                                    .build()
                                    )
                                    .build()
                    );
                }).build();
    }


}
