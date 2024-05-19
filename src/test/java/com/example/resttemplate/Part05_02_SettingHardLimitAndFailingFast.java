package com.example.resttemplate;


import com.example.resttemplate.Part05_02_SettingHardLimitAndFailingFast.NetworkCasesEmulatingController.TheTestController;
import com.example.resttemplate.Part05_02_SettingHardLimitAndFailingFast.NetworkCasesEmulatingController.TheTestController.DummyProduct;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
import org.testcontainers.shaded.org.awaitility.core.ThrowingRunnable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType.COUNT_BASED;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.HttpMethod.GET;

/**
 * Calls to our application need to come via http, so we need to start a server.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.compression.enabled=true",
                "server.compression.mime-types=application/json",
                "server.compression.min-response-size=10240"
        }
)
@Slf4j
public class Part05_02_SettingHardLimitAndFailingFast {

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

            // we'll reuse some dummy data for the responses, and reusing it will ensure that data is compressable
            static final List<String> NAMES_POOL = List.of("MacBook Pro", "iPhone 12", "iPad Pro", "Apple Watch", "AirPods");
            static final List<String> DESCRIPTIONS_POOL = List.of(
                    "The MacBook Pro is a line of Macintosh portable computers introduced in January 2006, by Apple Inc.",
                    "The iPhone 12 and iPhone 12 Mini are smartphones designed, developed, and marketed by Apple Inc.",
                    "The iPad Pro is a line of iPad tablet computers designed, developed, and marketed by Apple Inc.",
                    "Apple Watch is a line of smartwatches produced by Apple Inc.",
                    "AirPods are wireless Bluetooth earbuds created by Apple Inc."
            );

            record DummyProduct(
                    String id,
                    String name,
                    String description
            ) {
            }

            // this is a "trick" not to compress the response
            @GetMapping(value = "/responses", produces = "application/octet-stream")
            public String getResponses(
                    @RequestParam(required = false, defaultValue = "0") int count
            ) throws JsonProcessingException {
                List<DummyProduct> products = generateProducts(count);
                return new ObjectMapper().writeValueAsString(products);
            }

            // application/json is compressible according to the configuration
            @GetMapping(value = "/compressed-responses", produces = "application/json")
            public List<DummyProduct> getCompressedResponses(
                    @RequestParam(required = false, defaultValue = "0") int count
            ) {
                return generateProducts(count);
            }


            // Such single product on average will be ~100 bytes
            private List<DummyProduct> generateProducts(int count) {
                List<DummyProduct> products = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    String id = RandomStringUtils.randomAlphanumeric(10);
                    int productIndex = new Random().nextInt(NAMES_POOL.size());
                    int descriptionIndex = new Random().nextInt(DESCRIPTIONS_POOL.size());
                    products.add(
                            new DummyProduct(
                                    id,
                                    NAMES_POOL.get(productIndex),
                                    DESCRIPTIONS_POOL.get(descriptionIndex)
                            )
                    );
                }
                return products;
            }

        }
    }

    /**
     * we need tomcat to be already running to allow testcontainers to call the port
     */
    @LocalServerPort
    int serverPort;

    ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withLogConsumer(new Slf4jLogConsumer(log));

    record ToxiProxySetup(
            String host,
            int port,
            eu.rekawek.toxiproxy.Proxy proxy
    ) {

    }

    /**
     * See documentation of TestContainers for more details on how to configure
     * networks in various cases.
     */
    @SneakyThrows
    private ToxiProxySetup setupToxiProxy() {
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
    void havingBandwidthLimit_compressionShouldHelp() throws Throwable {
        ToxiProxySetup proxySetup = setupToxiProxy();

        proxySetup.proxy().toxics().bandwidth("slow-down", ToxicDirection.DOWNSTREAM,
                // rate in kilobytes per second
                5);

        // we don't care about actual limits now
        RestTemplate restTemplate = buildRestTemplate();

        // we'll make a call to the endpoint that returns ~12-15kb of data
        Duration duration = timeIt(() -> {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "http://{ip}:{port}/responses?count=100",
                    String.class, proxySetup.host(), proxySetup.port()
            );
            HttpHeaders headers = response.getHeaders();
            assertThat(headers.getContentType().toString())
                    .contains("text/plain");
            assertThat(headers.getContentLength())
                    .isBetween(12 * 1024L, 15 * 1024L);
            // parse body from JSON -> list will have 100 elements + some field values
            List<DummyProduct> objects = new ObjectMapper().readValue(response.getBody(), new TypeReference<>() {
            });
            assertThat(objects).hasSize(100);
            assertThat(objects.get(0).name()).isIn(TheTestController.NAMES_POOL);
            assertThat(objects.get(0).description()).isIn(TheTestController.DESCRIPTIONS_POOL);
        });
        assertThat(duration).isBetween(
                Duration.ofMillis(2500), Duration.ofMillis(3500)
        );

        // now let's see what happens when we call the endpoint eligible for compression
        Duration compressedDuration = timeIt(() -> {
            ResponseEntity<List<DummyProduct>> products = restTemplate.exchange(
                    "http://{ip}:{port}/compressed-responses?count=100",
                    GET, null,
                    new ParameterizedTypeReference<>() {
                    }, proxySetup.host(), proxySetup.port()
            );
            assertThat(products.getHeaders().getContentType())
                    .hasToString("application/json");
            assertThat(products.getBody()).hasSize(100);
            assertThat(products.getBody().get(0).name()).isIn(TheTestController.NAMES_POOL);
            assertThat(products.getBody().get(0).description()).isIn(TheTestController.DESCRIPTIONS_POOL);
        });
        assertThat(compressedDuration)
                .isLessThan(Duration.ofSeconds(1));

        // btw, new RestTemplate() will not support compression
        RestTemplate restTemplateWithoutCompression = new RestTemplate();
        Duration noCompressionSupportOnClient = timeIt(() -> {
            ResponseEntity<List<DummyProduct>> products = restTemplateWithoutCompression.exchange(
                    "http://{ip}:{port}/compressed-responses?count=100",
                    GET, null,
                    new ParameterizedTypeReference<>() {
                    }, proxySetup.host(), proxySetup.port()
            );
            assertThat(products.getHeaders().getContentType())
                    .hasToString("application/json");
            assertThat(products.getBody()).hasSize(100);
            assertThat(products.getBody().get(0).name()).isIn(TheTestController.NAMES_POOL);
            assertThat(products.getBody().get(0).description()).isIn(TheTestController.DESCRIPTIONS_POOL);
        });
        assertThat(noCompressionSupportOnClient).isBetween(
                Duration.ofMillis(2500), Duration.ofMillis(3500)
        );
    }

    @Test
    void timeoutsOnRestTemplateMayNotBeEnough() throws Exception {
        ToxiProxySetup setup = setupToxiProxy();

        setup.proxy().toxics().slicer(
                "slice", ToxicDirection.DOWNSTREAM,
                // size in bytes of the slice
                100,
                // delay between slices in microseconds, e.g. 1 second
                1_000_000
        );

        // socket timeout is 1 second, request timeout is 2 seconds
        var restTemplateWithTimeout = buildRestTemplateWithLimits();

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        // the server will respond quickly, but the client will receive the response in slices
        String rawResponse = restTemplateWithTimeout
                .getForObject("http://{ip}:{port}/responses?count=4", String.class,
                        setup.host(), setup.port()
                );
        // due to randomness of content, we can't predict the exact length
        assertThat(rawResponse.length()).isBetween(400, 700);
        stopWatch.stop();

        // and overall time will be way about timeouts that we set
        double time = stopWatch.getTotalTime(TimeUnit.SECONDS);
        assertThat(time).isBetween(4.0, 8.0);
    }

    @Test
    @SneakyThrows
    void hardLimit_canBeSetByAbortingNativeApacheRequest() {
        ToxiProxySetup setup = setupToxiProxy();

        setup.proxy().toxics().slicer(
                "slice", ToxicDirection.DOWNSTREAM,
                // avg size + delay in microseconds
                100, 1_000_000
        );

        var restTemplate = buildRestTemplateWithLimits();

        HttpComponentsClientHttpRequestFactory factory = (HttpComponentsClientHttpRequestFactory)
                restTemplate.getRequestFactory();

        ClientHttpRequestFactory factoryWithHardTimeout = (uri, httpMethod) -> {
            var request = factory.createRequest(uri, httpMethod);
            return requestWithHardTimeout(request, 3, TimeUnit.SECONDS);
        };
        restTemplate.setRequestFactory(factoryWithHardTimeout);

        // now let's see what happens when we fetch the data from "slow/sliced" endpoint
        assertThatExceptionOfType(RestClientException.class)
                .isThrownBy(() -> restTemplate.getForObject(
                        "http://{ip}:{port}/responses?count=4", String.class,
                        setup.host(), setup.port()
                )).havingCause()
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
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            log.info("Hard cancelling the request");
            apacheHttpRequest.cancel();
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

    /**
     * Applies timeout of 1 second to the connection pool and 2 seconds to the request.
     */
    private static RestTemplate buildRestTemplateWithLimits() {
        return buildRestTemplateWithLimits(
                Timeout.ofSeconds(1), Timeout.ofSeconds(2)
        );
    }

    /**
     * Will configure the connection pool's socket timeout and request's timeout.
     */
    private static RestTemplate buildRestTemplateWithLimits(
            Timeout socketTimeout, Timeout responseTimeout
    ) {
        return buildRestTemplateWithLimits(
                connConfig -> connConfig.setSocketTimeout(socketTimeout),
                reqConfig -> reqConfig.setResponseTimeout(responseTimeout)
        );

    }

    /**
     * Applies no customizations to the connection pool and request configuration.
     */
    private static RestTemplate buildRestTemplate() {
        return buildRestTemplateWithLimits(
                __ -> {
                }, __ -> {
                }
        );
    }

    private static RestTemplate buildRestTemplateWithLimits(
            Consumer<ConnectionConfig.Builder> poolConfigCustomizer,
            Consumer<RequestConfig.Builder> requestConfigCustomizer
    ) {
        ConnectionConfig.Builder connectionConfigBuilder = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(1));
        poolConfigCustomizer.accept(connectionConfigBuilder);

        RequestConfig.Builder requestConfigBuilder = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(1));
        requestConfigCustomizer.accept(requestConfigBuilder);

        return new RestTemplateBuilder()
                .requestFactory(() -> {
                    PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                            .setDefaultConnectionConfig(connectionConfigBuilder.build())
                            .build();
                    return new HttpComponentsClientHttpRequestFactory(
                            HttpClients.custom()
                                    .setConnectionManager(connectionManager)
                                    .setDefaultRequestConfig(requestConfigBuilder.build())
                                    .build()
                    );
                }).build();
    }

    private Duration timeIt(ThrowingRunnable runnable) throws Throwable {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        runnable.run();
        stopWatch.stop();
        return Duration.ofMillis(stopWatch.getTotalTimeMillis());
    }

}
