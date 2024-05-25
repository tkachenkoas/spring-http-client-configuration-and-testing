# Configuring Apache Client via HttpComponentsClientHttpRequestFactory

## Introduction
This article is a part of a series of articles about a deeper dive into
making http calls in Spring.

- [Part 1 - Options and testing](...)
- [Part 2 - Spring web client and test-kit](...)
- [Part 3 - ClientHttpRequestFactory as abstraction over http layer](...) 
- [Part 4 - Configuring Apache Client via HttpComponentsClientHttpRequestFactory](...) (you're here)
- [Part 5 - Setting hard limit on a request and failing fast](...)
- [Part 6 - Adding observability for web client](...)
  All source code is available in the [GitHub repository](https://github.com/tkachenkoas/spring-web-client-in-depth).

## Configurations that you should have in production

Until now, we only focused on functional happy paths — when we expected certain responses / headers / status codes.
But in a production environment, we should also be prepared to meet certain non-functional requirements:
- ensuring certain throughput
- making sure that calls are not blocked indefinitely
- making sure that calls do not take too long
- ...

Typical configurations for meeting these requirements are:
- reusing connections instead of creating a new connection for each request — to reduce TSL overhead
- properly configuring the connection pool not to exhaust resources
- setting certain timeouts to fail fast in case of a problem
- also, we need to be able to monitor the actual behavior of the client against the expected behavior

Base setup of mock server that we will use in the examples:

```
@MockServerSettings(ports = 1100)
@RequiredArgsConstructor
@Slf4j
public class Part04_01_ConfiguringNativeHttpClient {

    private final MockServerClient mockServer;

    @BeforeEach
    void reset() {
        mockServer.reset();
    }
    
    /** 
     * all the tests  
     */
    
}
    
```

First, let's take a short look at setting and testing timeout via `SimpleClientHttpRequestFactory`.
As you can see, if the remote server is not responding in a reasonable time,
we'll fail fast and not wait for all the time in the universe.


```
    @Test
    void anExampleOfSettingReadTimeout_viaSimpleClientHttpRequestFactory() {
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

        // and we'd rather not wait for all the 30 seconds for the response

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
```

And important note about the meaning of `readTimeout` property. In most http client, the actual execution is delegated
to JDK's `HttpURLConnection`, and here is the javadoc for `readTimeout` property:

```
Sets the read timeout to a specified timeout, in milliseconds. A non-zero value specifies the timeout when reading from 
Input stream when a connection is established to a resource. If the timeout expires before there is data available for 
read, a java. net. SocketTimeoutException is raised. A timeout of zero is interpreted as an infinite timeout.
Some non-standard implementation of this method ignores the specified timeout. To see the read timeout set, please call getReadTimeout().
```

Internally, `HttpURLConnection` will most likely delegate the actual work to the underlying `java.sun.NetworkClient` 
and `java.next.ServerSocket` low-level classes. What is important to understand is that `readTimeout` is NOT the request 
execution timeout. In simple words, it's the maximum allowed time between two bytes of data being received. So, if you set `readTimeout` to 
20 seconds, you're giving the server 20 seconds to go to DB, fetch the data, and start sending it to you. Now, if some
transport-level issues with bandwidth or chunking happen in the middle, the application will wait up to 20 seconds,
and in theory, the request can last MUCH longer than 20 seconds.

`MockServer` does not allow testing such cases in depth, but there are other tools like `ToxiProxy` that allow simulating
such network issues - and we will cover them in the next article.

## Configuring Apache HttpClient via HttpComponentsClientHttpRequestFactory

Apache HttpClient is an advanced http client that has many features, more configuration
options, and it's widely used in many production systems.

We'll look into configuring two important aspects of Apache HttpClient:
- connection pooling and reusing connections
- setting timeouts for the request and for the connection

First, let's check the behavior of connection pooling and reusing connections. In the example below, 
we are making 20 concurrent requests to the same server, and we have only 10 connections in the pool. 
This means that the second half of the requests will have to wait, and if you want bigger throughput, you should 
increase the pool size.

```
    @Test
    void anExampleOfConfiguringConnectionPool_andHowItCanAffectThroughPut() {
        RestTemplate restTemplate = new RestTemplate();

        // pooling connections saves time on handshaking and other https steps,
        // and it saves resources on both caller and receiver side
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(10);
        // in simple words, "route" is the target domain (host:port)
        connectionManager.setDefaultMaxPerRoute(10);

        restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setConnectionManager(connectionManager)
                        .build()
        ));

        // remote server takes 2 seconds to respond
        mockServer.when(
                request()
                        .withMethod("GET")
                        .withPath("/some-endpoint")
        ).respond(
                response()
                        .withStatusCode(200)
                        .withDelay(TimeUnit.SECONDS, 2)
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
        future.join();

        stopWatch.stop();
        var elapsed = stopWatch.getTotalTimeMillis();
        // even though we are making 20 requests concurrently,
        // we have only 10 connections in the pool
        // so the second half of the requests will have to wait
        assertThat(elapsed).isGreaterThan(4_000).isLessThan(4_500);
    }
```

One more example is about setting limits on the number of connections per route and choosing pool concurrency policy.
If you are using the same client to make requests to multiple hosts, you should limit the number of connections
per route. This is especially important if you are making a lot of requests to a server that requires processing time, 
and you don't want to exhaust the connection pool.

You can choose between `LAX` and `STRICT` pool concurrency policy. Under the hood, when lease request is made,
the pool will do some locking and unlocking, and `LAX` policy will allow more concurrency. This is useful when you
have a lot of concurrent and short requests. With `STRICT` policy, you might experience some starvation and spend more
time on locking and unlocking that on actual request processing.

Provided example illustrates that by limiting the number of connections per route, we can make 30 concurrent requests
to a fast server and 10 requests to a slow server, and these groups of requests will not interfere with each other.

```
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
```

The Next configuration example is about setting timeouts for the request. When building Apache http client with
`PoolingHttpClientConnectionManager`, you can set multiple parameters that will affect overall duration. The configuration
might be a bit misguiding, since you can configure `ConnectionConfig` and `RequestConfig` with similar parameters, and
you won't have any exception with the feeling that you've set inconsistent values.

The comments in the code are copy-pasted from corresponding javadocs. Pay attention that library defaults is usually not 
the configuration that you want to have in production. Not all the parameters can be properly unit-tested with `MockServer`
or any other tools (for instance, I don't know a wat to test `connectTimeout`). 

- Parameters of `org.apache.hc.client5.http.config.ConnectionConfig`:
  - `socketTimeout` - Determines the default socket timeout value for I/ O operations. Default: null (undefined)
  - `connectTimeout` - Determines the timeout until a new connection is fully established. A timeout value of zero is 
  interpreted as an infinite timeout. Default: 3 minutes. 
  - `timeToLive` - Defines the total span of time connections can be kept alive or execute requests.
    Default: null (undefined)
- Parameters of `org.apache.hc.client5.http.config.RequestConfig`:
  - `connectionRequestTimeout` - connection lease request timeout used when requesting a connection from the connection manager.
  - `responseTimeout` - Determines the timeout until arrival of a response from the opposite endpoint.
      A timeout value of zero is interpreted as an infinite timeout. Please note that response timeout may be unsupported 
      by HTTP transports with message multiplexing.

First, we'll illustrate the behavior of `connectionRequestTimeout`: if all connections are already in lease by pool
manager, we should fail fast: the value of this parameter should not be too high.

```
    @Test
    void limitedConnectionRequestTime_willThrowOnAllConnectionsBeingBlocked() {
        // we'll configure a single connection pool
        RestTemplate restTemplate = new RestTemplate();
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(1)
                .build();
        restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setConnectionRequestTimeout(Timeout.of(2, TimeUnit.SECONDS))
                                        .build()
                        ).setConnectionManager(manager)
                        .build()
        ));

        // remote server is not responding in reasonable time
        mockServer.when(request().withMethod("GET").withPath("/some-endpoint"))
                .respond(response().withStatusCode(200).withDelay(TimeUnit.SECONDS, 5));

        // this async operation will block the only connection in the pool
        CompletableFuture.runAsync(() -> restTemplate.getForObject("http://localhost:1100/some-endpoint", String.class));

        // connection manager has api that can be used to monitor the pool
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
                .hasCauseInstanceOf(ConnectionRequestTimeoutException.class)
                .hasMessageContaining("Timeout deadline: 2000 MILLISECONDS, actual:");
    }
```

Now, let's take a look at the "conflicting" parameter of `socketTimeout` of connection pool and `responseTimeout` of
the client's request config. The actual behavior is that the `responseTimeout` will be used and will override the
`socketTimeout` of the connection pool. If you want to put a breakpoint, here is the place:
`org.apache.hc.client5.http.impl.classic.InternalExecRuntime#execute(String, ClassicHttpRequest, HttpClientContext)`

Here is detailed example that illustrates various cases when exception will be thrown, and when - it won't

```
    private RestTemplate buildWithTimeouts(
            Timeout socketTimeout,
            Timeout responseTimeout
    ) {
        RestTemplate restTemplate = new RestTemplate();

        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                                .setSocketTimeout(socketTimeout)
                                .build()
                )
                .build();
        restTemplate.setRequestFactory(new HttpComponentsClientHttpRequestFactory(
                HttpClients.custom()
                        .setDefaultRequestConfig(
                                RequestConfig.custom()
                                        .setResponseTimeout(responseTimeout)
                                        .build()
                        ).setConnectionManager(manager)
                        .build()
        ));
        return restTemplate;
    }

    @Test
    void requestTimeoutOfTheClient_willOverrideSocketTimeoutOfConnectionPool() {
        // configure endpoint to respond in 2 seconds
        mockServer.when(request().withMethod("GET").withPath("/some-endpoint"))
                .respond(response()
                        .withBody("hello")
                        .withStatusCode(200).withDelay(TimeUnit.SECONDS, 2));

        // set only socket timeout
        RestTemplate onlySocketAndAbove = buildWithTimeouts(
                Timeout.ofSeconds(5), null
        );
        assertThat(onlySocketAndAbove.getForObject("http://localhost:1100/some-endpoint", String.class))
                .isEqualTo("hello");

        // if socket timeout is less than server delay time, it will get an exception
        RestTemplate onlySocketAndBelow = buildWithTimeouts(
                Timeout.ofSeconds(1), null
        );
        assertThatThrownBy(() -> onlySocketAndBelow.getForObject("http://localhost:1100/some-endpoint", String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);

        // set only response timeout
        RestTemplate onlyResponseAndAbove = buildWithTimeouts(
                null, Timeout.ofSeconds(5)
        );
        assertThat(onlyResponseAndAbove.getForObject("http://localhost:1100/some-endpoint", String.class))
                .isEqualTo("hello");

        // if response timeout is less than server delay time, it will get an exception
        RestTemplate onlyResponseAndBelow = buildWithTimeouts(
                null, Timeout.ofSeconds(1)
        );
        assertThatThrownBy(() -> onlyResponseAndBelow.getForObject("http://localhost:1100/some-endpoint", String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);

        // set both socket and response timeout, but socket timeout is less than server delay time
        RestTemplate bothAndSocketIsBelow = buildWithTimeouts(
                Timeout.ofSeconds(1), Timeout.ofSeconds(5)
        );
        // response timeout wins
        assertThat(bothAndSocketIsBelow.getForObject("http://localhost:1100/some-endpoint", String.class))
                .isEqualTo("hello");

        // not response timeout is less than server delay time
        RestTemplate bothAndResponseIsBelow = buildWithTimeouts(
                Timeout.ofSeconds(5), Timeout.ofSeconds(1)
        );

        assertThatThrownBy(() -> bothAndResponseIsBelow.getForObject("http://localhost:1100/some-endpoint", String.class))
                .isInstanceOf(ResourceAccessException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
    }
```

I'll continue with a more complex case of limiting full request execution time in the next article.

## Short conclusion of Part 4

In this article, we've seen how to use Apache HttpClient in `RestTemplate` via `HttpComponentsClientHttpRequestFactory`,
how and why connection pooling is important, and how to set socket timeouts for the request and connection.

My advice regarding these configs is to have tests for them independently of the business-logic tests to be able to
verify expected behavior on simple examples and to make sure that you deliver the expected behavior in production.

In case you hesitate whether to use `MockServer` or `MockRestServiceServer`, I suggest the following rule of thumb:
- if you're testing the logic/interaction of your component with a remote API via `RestTemplate`, 
  use `MockRestServiceServer`. It's very flexible and even allows you to test the "real" URL.
- if you're testing how your rest client makes http calls, use `MockServer`. In contrast to `MockRestServiceServer`,
  it's not replacing a real layer of your application (e.g. http client), thus making tests more honest.