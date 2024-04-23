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
  network misbehavior. And this is where
  we'll introduce mutations to see how the configuration of http client can protect us.

## Application & test setup

We have a simple controller that will return a list of products. The controller has two endpoints:

- `/responses` that will return a list of products as a JSON string (but not as application/json). And
  the only mime-type that supports compression according to config is `application/json`.
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
    private static RestTemplate buildRestTemplate() {
        return buildRestTemplateWithLimits(__ -> {}, __ -> {});
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
any configuration: this can make a huge difference in performance of fetching big chunks of data.

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