# Configuring Apache Client via HttpComponentsClientHttpRequestFactory

## Introduction
This article is a part of a series of articles about a deeper dive into
making http calls in Spring.

- [Part 1 - Options and testing](...)
- [Part 2 - Spring web client and test-kit](...)
- [Part 3 - ClientHttpRequestFactory as abstraction over http layer](...) 
- [Part 4 - Configuring Apache Client via HttpComponentsClientHttpRequestFactory](...) (you're here)
- [Part 5 - Adding observability for web client](...)
- [Part 6 - A deeper glance into non-happy-path scenarios](...)
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
public class Part04_ConfiguringNativeHttpClient {

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
execution timeout. In simple words, it's the time between two bytes of data being received. So, if you set `readTimeout` to 
20 seconds, you're giving the server 20 seconds to go to DB, fetch the data, and start sending it to you. Now, if some
transport-level issues with bandwidth or chunking happen in the middle, you will wait upd to 20 seconds for each next byte of data,
and in theory, the request can last MUCH longer than 20 seconds.

`MockServer` does not allow to test such cases in depth, but there are other tools like `ToxiProxy` that allow to simulate
such network issues - and we will cover them in the next article.

## Configuring Apache HttpClient via HttpComponentsClientHttpRequestFactory

Apache HttpClient is a more advanced http client than the one provided by JDK. It has more features, more configuration
options, and it's widely used in production. 

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

Next configuration example is about setting timeouts for the request. There are multiple timeouts that you can set
and they have different meanings. Unfortunately, not all of them can be properly tested with `MockServer`.
The comments in the code are copy-pasted from corresponding javadocs. As you can see, default values are quite big,
and you might want to set them to something more reasonable for your use cases.

- `connectTimeout` - the time to establish the connection with the remote server
- `socketTimeout` - the time to wait for the data to be received from the remote server. E.g. this is the interval
  between two packets of data being received
- `connectionRequestTimeout` - the time to wait for a connection from the connection manager/pool (if all connections
  are currently being used, and the pool is exhausted, you'll get a timeout exception)
- `responseTimeout` - the time to wait for the response from the remote server. E.g. this is the interval between the
  client sending the request and the server reacting with anything ( headers, body)

In the provided example, we're creating a "single-connection" pool and blocking the connection for 5 seconds with
a slow response. We can also access the connection pool stats and verify that the connection is leased.

```
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
```

One more example is about setting limits on the number of connections per route and choosing pool concurrency policy.
If you are using same client to make requests to multiple host, you might want to limit the number of connections
per route. This is especially important if you are making a lot of requests to a slow server, and you don't want
to exhaust the connection pool.

Also you can choose between `LAX` and `STRICT` pool concurrency policy. Under the hood, when lease request is made,
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