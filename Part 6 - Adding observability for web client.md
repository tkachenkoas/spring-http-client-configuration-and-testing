# Adding observability for web client

## Introduction

This article is a part of a series of articles about a deeper dive into
making http calls in Spring.

- [Part 1 - Options and testing](...)
- [Part 2 - Spring web client and test-kit](...)
- [Part 3 - ClientHttpRequestFactory as abstraction over http layer](...)
- [Part 4 - Configuring Apache Client via HttpComponentsClientHttpRequestFactory](...)
- [Part 5 - Setting hard limit on a request and failing fast](...)
- [Part 6 - Adding observability for web client](...) (you're here)
  All source code is available in the [GitHub repository](https://github.com/tkachenkoas/spring-web-client-in-depth).

## Spring's default observability support

Spring ecosystem provides a whole module dedicated to observability - `spring-boot-starter-actuator`. Under the hood,
it uses the Micrometer library to collect metrics and also the integration with Prometheus and other popular monitoring
systems. This article will not be about how to use Prometheus and Grafana, but rather how to add observability to
your web client and also how not to accidentally hurt the performance of your application.

The core component for Micrometer is `MeterRegistry`. If you search project and libraries in IDE for following
`@ConditionalOnBean(MeterRegistry.class)`, you will find a lot of systems for which Spring Boot autoconfigures
monitoring. For instance, `MongoMetricsAutoConfiguration` and `TomcatMetricsAutoConfiguration` will provide you with
metrics for MongoDB and Tomcat, respectively.

Also there is `HttpClientObservationsAutoConfiguration` which also includes `RestTemplateObservationConfiguration`
that defines a bean of `ObservationRestTemplateCustomizer`. Long story short, this means that if you have
`MeterRegistry` bean AND you use Spring's beans to build your`RestTemplate` via `RestTemplateBuilder`, then all
the observability will be applied out of the box. This will not work is you instantiate `RestTemplate` manually.

## Http client metrics collected out of the box 

The setup for exploring rest template metrics is plain. We fully rely on Spring Boot autoconfiguration and
will use the default `SimpleMeterRegistry` from spring-boot-starter-actuator. Also we will use the
`RestTemplateBuilder` from context to build `RestTemplate` instances.

```

@SpringBootTest
@MockServerSettings(ports = 8090)
@RequiredArgsConstructor
@Slf4j
public class Part06_ObservabilityAndMonitoring {

    private final ClientAndServer mockServer;

    @SpringBootApplication
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

...

```

In first example, we make a GET request to a mock server, and then we check the metrics collected by
`SimpleMeterRegistry`. The metrics are collected by `http.client.requests` meters. The metrics will
contain information about executed requests, and also about active requests. They will allow you to
build graphs of total requests and average request time per uri/status. Unfortunately, richer statistical 
information like distribution of request time is not available.   

```
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
```

However, there is a catch. If you misuse `RestTemplat`'s api by invoking multiple different uri templated 
due to String concatenation, then you will end up with a lot of metrics. This is because the `UriTemplate` is used 
as a key to store the metrics. If your application makes lots of different requests with different URIs, then you might
accidentally find that you have high CPU usage and response size of `/actuator/prometheus` to be megabytes 
or even tens of megabytes.

```
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
```

Lastly, if you configured `RestTemplate` with apache client powered by connection pool, then you can also monitor
its metrics. The `PoolingHttpClientConnectionManager` has api to get information about the pool state. 
And micrometer has a utility class `PoolingHttpClientConnectionManagerMetricsBinder` that acts as an adapter
from `PoolingHttpClientConnectionManager` to `MeterRegistry`. Thus, you can get live information about the pool - 
how many connections are leased, how many are available, etc.

```
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
```

## Short conclusion of Part 6

Like any other part of the Spring ecosystem, Spring's web client has built-in support for observability.
You will get it out of the box if you use  `@Autowired RestTemplateBuilder rtb` to build `RestTemplate` instances.
If you have prometheus or another monitoring system configured,
check, how many metrics are collected from your web client,
and if needed, disable it.
Remember also to monitor the connection pool and adjust its size according to your needs.

# General conclusion for the series of articles

These articles can't be considered as a complete guide to Spring's web client or to making http calls in general. 
Nor it can replace you the effort of reading the official documentation and the source code of the libraries.

My goal was to share my personal experience and to show you some of the pitfalls that you might encounter. 
One of the core focuses was not only to mention a certain feature/configuration options,
but also to create a reproducible example that allows to reproduce the use case and to play with it.

I hope that you found these articles useful and that you will be able to apply the knowledge in your projects. If you
discovered any issues in the provided examples, feel free to create an issue in the comments or in 
[GitHub repository](https://github.com/tkachenkoas/spring-web-client-in-depth)