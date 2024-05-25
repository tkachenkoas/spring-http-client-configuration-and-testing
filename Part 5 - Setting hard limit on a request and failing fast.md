# Setting hard limit on a request and failing fast

## Introduction

This article is a part of a series of articles about a deeper dive into
making http calls in Spring.

- [Part 1 - Options and testing](...)
- [Part 2 - Spring web client and test-kit](...)
- [Part 3 - ClientHttpRequestFactory as abstraction over http layer](...)
- [Part 4 - Configuring Apache Client via HttpComponentsClientHttpRequestFactory](...)
- [Part 5 - Setting hard limit on a request and failing fast](...) (you're here)
- [Part 6 - Adding observability for web client](...)
  All source code is available in the [GitHub repository](https://github.com/tkachenkoas/spring-web-client-in-depth).

## Understanding long requests and properly imitating them

In the previous article, we've only scratched the case of long requests and imitated them by setting a response delay
with `MockServer`. In real world, following scenarios (and not only) can lead to long requests:

- slow network (e.g. low bandwidth)
- bad network (e.g. data slicing with delays, packet loss...)
- either-side misconfiguration not allowing to use compression
- ... and many more

To imitate these cases, we'll do the following:

- write a test application setup that will allow to access a controller via http and tomcat. The controller itself
  will just behave normally (but will allow us to configure the amount of data it will return and whether it will be
  compressed).
- send all the requests to the controller via [Toxiproxy](https://github.com/Shopify/toxiproxy) that will introduce
  network misbehavior. And this is where we'll introduce mutations to see how the configuration of http client can protect us.

## Application & test setup

We have a simple controller that will return a list of products. The controller has two endpoints:

- `/responses` that will return a list of products as a text/plain string that is a JSON (but not as application/json). 
  And the only mime-type that supports compression according to `server.compression.mime-types` is `application/json`.
- `/compressed-responses` that will return a list of products as a JSON string with `application/json` mime-type.

```
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
    static class NetworkCasesEmulationApplication {

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
    
}
```

## ToxiProxy setup

ToxiProxy also comes as a [TestContainer](https://java.testcontainers.org/modules/toxiproxy/), so you can start using
it pretty easy. The documentation for setup and usage is great, and provides multiple examples. I will just show very
basic usage of it.

```

    /** we need tomcat to be already running to allow testcontainers to call the port */
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

```

## Exploring performance of compressed and non-compressed endpoints

This is what test helper methods look like:

```
    /** Applies timeout of 1 second to the connection pool and 2 seconds to the request. */
    private static RestTemplate buildRestTemplateWithLimits() {
        return buildRestTemplateWithLimits(
                Timeout.ofSeconds(1), Timeout.ofSeconds(2)
        );
    }

    /** Will configure the connection pool's socket timeout and request's timeout. */
    private static RestTemplate buildRestTemplateWithLimits(
            Timeout socketTimeout, Timeout responseTimeout
    ) {
        return buildRestTemplateWithLimits(
                connConfig -> connConfig.setSocketTimeout(socketTimeout),
                reqConfig -> reqConfig.setResponseTimeout(responseTimeout)
        );

    }

    /** Applies no customizations to the connection pool and request configuration. */
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
    
    private Duration timeIt(Runnable runnable) {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        runnable.run();
        stopWatch.stop();
        return Duration.ofMillis(stopWatch.getTotalTimeMillis());
    }
```

Let's take a look at comparing response time of fetching 100 "products" from endpoint with limited bandwidth
with compression and without compression. Pay attention to the third call that uses `RestTemplate` without 
any configuration: this can make a huge difference in performance of fetching big chunks of data. Whether compression
will actually be applied or not depends both on the client AND server configuration.

```
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
```

## Exploring behavior of timeouts in real-world scenarios 

Now let's see how timeout configurations can behave when application meets some disturbances in the network. 
In the example below, we set connection pool timeout to 1 second and request timeout to 2 seconds. 
The endpoint will return data with ~1 second delay between every 100 bytes of data. The overall time of the request 
will be way above the timeouts that we set. It is common to misunderstand the actual meaning of timeouts: 
`timeout` is the time that the client will wait for next byte of data to arrive, not overall maximum execution 
time of the request. Thus, even if your client is configured with certain timeouts, some real-world scenarios 
can still lead to long requests.

```
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
```

## Configuring hard total timeout on the request

If we want to set real "hard" limit on the request execution, we can leverage apache client's `HttpUriRequestBase#cancel` method 
and schedule it to be called after certain amount of time. However, this raises an issue reaching the actual 
HttpUriRequestBase` from `RestTemplate` high-level API. Below is an example of how to do it by using reflection 
and decorating the request from `HttpComponentsClientHttpRequestFactory`. Alternatively, there is a way to 
use `ClientHttpRequestInterceptor` of `RestTemplate` or `org.apache.hc.core5.http.HttpRequestInterceptor` to 
configure `HttpClientBuilder`.

Note that aborting request is a complex mechanism that relies on setting a "canceled" flag in the request object, and
actual cancellation will happen only after certain logic responsible for sending the request will check this flag. Think
of it as something similar to "interrupting" a thread in Java: it's not guaranteed that the running task will stop,
unless certain logic in the flow will check the "interrupted" flag and throw an exception.

```
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
```

However, I do not recommend aborting requests, because:
- it's relying on too many internal details of the factory powering the `RestTemplate`. Thus, changes in the factory
  implementation can break your code
- it's not guaranteed that request object can be casted to `Cancellable` in all flows - thus logic will become more complex
- concurrency and cancellation might mess up with some lifecycle of the request / connection - and you might end up
  getting strange exceptions/warnings that are not reproducible in unit tests 
- consistent cancellation of requests usually indicates that something goes wrong, and a better approach is to
  fail-fast even before the request is sent

## Recommended way of dealing with requests that take too long

If you know that certain requests can take much time, a good approach is usually to make them run in background so 
that synchronous operations are not blocked. However, you still what to have a safety mechanism to protect
your application from being overwhelmed by too many ultra-long requests that bypass your basic timeout mechanism.
Setting hard limits is not a good idea also for the following reason:
- suppose, you have configured general timeouts to be ~10 seconds
- but you know that normally requests should not take more than 2-3 seconds, and maybe you want to also set a
    hard limit of 15 seconds
- however, some disruptions in the network started to cause all your requests to take 25 seconds
- this means that now ALL your requests will be failing, and they will also block resources for whole
  duration of 'sufficient' 15-second limit; and you probably don't want this behavior

A classic approach is to use `Circuit breaker` pattern. The idea behind it is following:
- when a certain number of requests fail or take too long, we need to stop calling the service - this is 
called "opening the circuit"; instead, an exception will be thrown immediately - and we'll follow "failing fast" principle
- however, after some time (or a manual action), calls should be allowed again to check if the service is back to normal

Probably the best library to use for this purpose is `Resilience4j`. Please refer to the documentation for more details: 
[R4J Circuit Breaker](https://resilience4j.readme.io/docs/circuitbreaker). By the way, it provides implementation of 
many other resilience patterns like `RateLimiter`, `Retry`, `Bulkhead` and others.

The lifecycle and configuration of the circuit breaker is very powerful (and complex), and it's hard to 
provide a "one-size-fits-all" configuration. Thus, when decorating a certain operation with a circuit breaker, you 
need to test its behavior very carefully and also to have good monitoring of the circuit breaker's state. The 
example below is basic, yet it shows an example how `CircuitBreaker` in pair with `ClientHttpRequestInterceptor` 
to protect your application from being disrupted by too many long or failing requests.

```
    @Test
    void whenSomethingGoesWrongConsistently_weShouldShortCircuitCalls() throws Exception {
        ToxiProxySetup setup = setupToxiProxy();

        // let's imagine that service consistently returns slow responses
        setup.proxy().toxics().slicer(
                "slice", ToxicDirection.DOWNSTREAM,
                // avg size + delay in microseconds
                100, 500_000
        );

        // this operation will succeed, but it will be slow
        RestTemplate restTemplate = buildRestTemplateWithLimits();
        Duration duration = timeIt(() -> restTemplate.getForObject(
                "http://{host}:{port}/responses?count=1",
                String.class, setup.host(), setup.port()
        ));
        assertThat(duration).isBetween(Duration.ofMillis(1000), Duration.ofMillis(2000));

        CircuitBreaker circuitBreaker = CircuitBreaker.of(
                "slow-service", CircuitBreakerConfig.custom()
                        // 500ms is the threshold for slow calls
                        .slowCallDurationThreshold(Duration.ofMillis(500))
                        // and 90% of calls should be slow for the circuit breaker to open
                        .slowCallRateThreshold(90)
                        // however, we'll accumulate statistics of at least 4 calls
                        .minimumNumberOfCalls(4)
                        // e.g. only 10 calls will be taken into account for calculating statistics
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

        // now we configure the rest template to use the circuit breaker via interceptor
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            log.info("Current state before call: {}", circuitBreaker.getState());
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
                    "http://{host}:{port}/responses?count=1",
                    String.class, setup.host(), setup.port()
            )).doesNotThrowAnyException();
        }

        // but if we make 4th, 5th, 6th and 7th call - they will fail
        for (int i = 0; i < 4; i++) {
            assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(() -> {
                        restTemplate.getForObject(
                                "http://{host}:{port}/responses?count=1",
                                String.class, setup.host(), setup.port()
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

        // and the call will succeed
        assertThatCode(() -> restTemplate.getForObject(
                "http://{host}:{port}/responses?count=1",
                String.class, setup.host(), setup.port()
        )).doesNotThrowAnyException();

        // now we remove our "toxin" to emulate that service is back to normal
        setup.proxy().toxics().get("slice").remove();

        // even though the target recovered, some calls may still fail because
        // previous calls in "suspicious" HALF_OPEN state were still slow

        assertThatExceptionOfType(ResourceAccessException.class).isThrownBy(() -> {
                    restTemplate.getForObject(
                            "http://{host}:{port}/responses?count=1",
                            String.class, setup.host(), setup.port()
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
                    "http://{host}:{port}/responses?count=1",
                    String.class, setup.host(), setup.port()
            )).doesNotThrowAnyException();
        }

        // and once service got back to normal, circuit breaker will be closed
        assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
    }
```

# Short conclusion of Part 5

This article illustrates how the real world can "by-pass" basic timeout configurations and how to 
emulate such cases it "fair" way with `ToxiProxy`. It also shows how to set hard limits on the 
request execution, and why a better approach is to use `CircuitBreaker`.
However, the most practical recommendation in this article isto check if your applications are
using `new RestTemplate()` to make calls in production. If you don't have good monitoring,
you might be surprised how long some requests actually take, and how many resources they waster.