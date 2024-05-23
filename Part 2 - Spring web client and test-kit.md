## Spring's web client and test-kit

## Introduction
This article is a part of a series of articles about a deeper dive into 
making http calls in Spring.

- [Part 1 - Options and testing](...) 
- [Part 2 - Spring web client and test-kit](...) (you're here)
- [Part 3 - ClientHttpRequestFactory as abstraction over http layer](...)
- [Part 4 - Configuring Apache Client via HttpComponentsClientHttpRequestFactory](...)
- [Part 5 - Setting hard limit on a request and failing fast](...)
- [Part 6 - Adding observability for web client](...)
  All source code is available in the [GitHub repository](https://github.com/tkachenkoas/spring-web-client-in-depth).

## The `Template` and `Operations` pattern in Spring

If you are using Spring ecosystem for web, db, and other integrations, you might have noticed that it has a lot of classes
named `SomethingTemplate` or `SomethingOperations`. For example, `JdbcTemplate`, `MongoTemplate`, `RestTemplate`,
`TransactionTemplate` (and they implement `JdbcOperations`, ... interfaces). This is a pattern that Spring uses to provide
a convenient way to work with some external system (like a database, a web service, etc.). Generally, the `Template`
classes follow similar strategy:
- they are thread-safe, so you can use the instance across your application
- they act as facades - e.g., provide nice and convenient api to work with the external system, but hide the complexity 
- they also support rich customization and extendability via interceptors, callbacks, customizers, event listeners, etc. 
Thus, your business-logic will depend only on relatively simple `Operations` interface, but full-context implementation
can be done in configuration classes
- another important benefit of using the `Template` classes is that they often throw Spring's runtime exceptions no matter 
what the underlying implementation is.

Usually, the `Template` classes are built on top of some "native" client (like `java.net.HttpURLConnection`,
mongo-java-driver, jedis etc.), instead of providing a new implementation from scratch. This means that you can always 
use the "native" client if you need - but you will lose the convenience and the consistency that the `Template` provides. 
In my opinion, whenever you need to work with some external system, you should first check if Spring has a `Template` for 
it, and only then consider using a "native" client.

### But RestTemplate is deprecated! Don't you know how to google?

Well, as of the date of writing this article, `RestTemplate` is not deprecated. It's true that Spring 6.1 / Spring Boot 3.2 
has introduced a new interface `RestClient` for synchronous operations, and it's true that Spring suggests using it 
instead of `RestTemplate`.

This is written in the documentation of `RestTemplate`:

```
     NOTE: As of 6.1, RestClient offers a more modern API for synchronous HTTP access.
     For asynchronous and streaming scenarios, consider the reactive
     org.springframework.web.reactive.function.client.WebClient.
```

So essentially, the `RestClient` is just another interface that provides the same functionality as `RestTemplate`. 
But you can still use `RestTemplate` and will be just fine.

If you check package `org.springframework.web.client` you will also see that it has `RestOperationsExtensionsKt` class
that provides kotlin extension functions to make `RestTemplate` even more convenient to use.

### Sample usage of RestTemplate

Here is an example of just making a simple GET request with `RestTemplate`:

```
    @Test
    void sampleUsageOfRestTemplate() {
        var restTemplate = new RestTemplate();

        String url = "http://localhost:1090/some-endpoint";
        SampleResponseModel getResponse = restTemplate.getForObject(url, SampleResponseModel.class);
        assertThat(getResponse).isEqualTo(new SampleResponseModel("John", 25));
    }
```

As you can see, `RestTemplate` is very convenient to use, and it also has built-in support for parsing the response body
into an object.  In general, you SHOULD NOT just use `new RestTemplate()` in production (and to make additional configs), 
and it will be covered later in the articles.

Here is a more complex example of setting headers/cookies. In this case, we have to use `RequestEntity` to set the
headers,
and then make a call with `RestTemplate.exchange` that returns `ResponseEntity` with the response body and headers

```
    @Test
    void sampleUsageOfSettingHeaders() throws Exception {
        // example of setting cookies / headers
        String url = "http://localhost:1090/some-endpoint-with-cookies";

        mockServer.when(
                org.mockserver.model.HttpRequest.request()
                        .withMethod("GET")
                        .withHeader("Cookie", "name=value")
                        .withHeader("Authorization", "Bearer token")
        ).respond(
                org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withBody("{\"name\":\"John\",\"age\":25}")
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Set-Cookie", "name=value")
        );


        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Cookie", "name=value");
        httpHeaders.add("Authorization", "Bearer token");

        RequestEntity<?> request = new RequestEntity<>(
                httpHeaders, HttpMethod.GET, new URI(url)
        );

        ResponseEntity<SampleResponseModel> response = new RestTemplate().exchange(request, SampleResponseModel.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("John");
        assertThat(response.getBody().age()).isEqualTo(25);
        assertThat(response.getHeaders().get("Set-Cookie")).contains("name=value");
    }
```

But one of my favorite features of `RestTemplate` is the ability to use url patterns with path variables or query
parameters. As you can see, this api satisfies the criteria of 
```one line of your action in human-readable language is represented by one line of code in the library```.

```
    @Test
    void sampleUsageOfMakingAPostWithPathVariables() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer.when(
                org.mockserver.model.HttpRequest.request()
                        .withMethod("POST")
                        .withPath("/some-endpoint/John")
                        .withQueryStringParameter("age", "25")
        ).respond(
                org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"John\",\"age\":25}")
        );

        String urkWithTemplate = "http://localhost:1090/some-endpoint/{name}?age={age}";
        SampleResponseModel someResponse = restTemplate.postForObject(
                urkWithTemplate, new SampleResponseModel("John", 25),
                SampleResponseModel.class,
                "John", 25
        );
        assertThat(someResponse).isEqualTo(new SampleResponseModel("John", 25));
    }
```

## Ok, and what about `RestClient`?

Under the hood the implementation of `RestClient` (`DefaultRestClient`) uses same abstractions as `RestTemplate`,
and it can be built on top of existing `RestTemplate`. This means that after you upgrade to Spring 6.1, you can
reuse benefit from using new api, but still use your properly configured `RestTemplate` instance. However, everywhere 
in the article I will use `RestTemplate` because its api is still a current default standard.

Here is an example of how to do use `RestClient`:

```
    @Test
    void sampleUsageOfRestClientInterface() {
        // the way to adapt your existing RestTemplate to RestClient
        RestClient restClient = RestClient.builder(new RestTemplate()).build();

        mockServer.when(
                org.mockserver.model.HttpRequest.request()
                        .withMethod("GET")
                        .withHeader("Accept", "application/json")
                        .withPath("/some-endpoint/John")
                        .withQueryStringParameter("age", "25")
        ).respond(
                org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"John\",\"age\":25}")
        );

        // now we can use the new interface
        String url = "http://localhost:1090/some-endpoint/{name}?age={age}";
        var getResponse = restClient.get()
                .uri(url, "John", 25)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(SampleResponseModel.class);

        assertThat(getResponse).isEqualTo(new SampleResponseModel("John", 25));
    }
```

As you can see, the `RestClient`'s api is more fluent and convenient to use. It also provides support 
for creating a proxy for a "typed" interface that represents the API. Here is an example:

```

    @Test
    void usingRestClientWithInterfacedDefinedProxy() {

        interface RemoteServiceAsInterface {
            @org.springframework.web.service.annotation.GetExchange("/some-endpoint/{name}")
            SampleResponseModel getSampleResponseModel(
                    @org.springframework.web.bind.annotation.PathVariable String name,
                    @org.springframework.web.bind.annotation.RequestParam Integer age
            );
        }

        mockServer.when(
                org.mockserver.model.HttpRequest.request()
                        .withMethod("GET")
                        .withPath("/some-endpoint/John")
                        .withQueryStringParameter("age", "25")
        ).respond(
                org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"John\",\"age\":25}")
        );


        HttpServiceProxyFactory factory = HttpServiceProxyFactory.builder()
                // supports both RestTemplate and RestClient
                /*.exchangeAdapter(RestTemplateAdapter.create(new RestTemplateBuilder()
                        .rootUri("http://localhost:1090").build()
                ))*/
                .exchangeAdapter(RestClientAdapter.create(RestClient.builder()
                        .baseUrl("http://localhost:1090")
                        .build()))
                .build();

        RemoteServiceAsInterface remoteServiceProxy = factory.createClient(RemoteServiceAsInterface.class);

        SampleResponseModel responseModel = remoteServiceProxy.getSampleResponseModel("John", 25);

        assertThat(responseModel).isEqualTo(new SampleResponseModel("John", 25));
    }

```

But even though this "typed" proxy is very convenient to use, please don't write tests that use some mock-implementation
or ```Mockito.when().thenReturn()``` to test the interaction with the proxy.

## A better alternative to type-safe proxy for the API

The interface-based proxy that is available in `Retorfit`, `Jersey` and `RestClient` is a very convenient way to
work with the API. However, it's not always the best way to do it. Lots of APIs are documented with OpenAPI/Swagger,
and there are tools that can generate the client code for you.

Some time ago I wrote an article about how to use `openapi-generator` to generate the client code for testing your
server-side code. You can find it here: https://medium.com/duda/cleaner-spring-boot-it-rest-tests-with-client-generation-cc3ac880d9ec

The main benefit of using the generated client code is zero manually written code. Components that you will use will be
generated from the API definition that the server-side team provides for you. This means that you can replace tests with
mock-server with tests on Mockito mocks, or even subclassed reimplementations to support integration cases. In this
 case, it is a "legal" action because it's not your code, and it's as reliable as its OpenAPI definition is.

If you are the one who is responsible for the API, you should also consider investing time into properly documenting
the API so that its consumers can generate the client code in any language they want. Also, a good practice for maintaining
the generated schema is this:
- store the schema in the version control system
- update the schema as part of the build process (and fail the build if the schema is not up to date)
- verify that changes in code are correctly reflected in the schema and that you don't introduce unexpected breaking changes

## Spring's test kit for RestTemplate / RestClient

A significant advantage of using `RestTemplate` or `RestClient` is that Spring provides a built-in support for testing
the code that uses these classes. This is done via `MockRestServiceServer`, which is a part of `spring-test` module,
and also via `@RestClientTest` annotation that is a part of `spring-boot-test` and allows you to start only a
slice of all auto-configurations available in your project, but to configure the context to work with rest clients.

Now let's take a look at the tiny application and the test that focuses on the `RestTemplate`

First, I need to remind you that for demonstration purposes, I've got a pretty "fat" build.gradle file:

```
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
```

If I were to write a `@SpringBootTest` test, it would load all the auto-configurations for all the databases, and
since I have not configured any mandatory properties for them (like database driver, url, ...), the context would fail.

However, if I use `@RestClientTest`, only the autoconfiguration for the rest clients will be loaded,
and as free additional benefit, `MockRestServiceServer` will be autoconfigured for the rest-template. Be careful, though,
because Spring will try to initialize all your beans that are in scope of `@SpringBootApplication` class,
thus typically you would want to use `@ContextConfiguration` to limit the scope of the context to the class under test.

```
@RestClientTest
public class Part02_02_SpringWebClientTestKit {

    @SpringBootApplication
    static class SpringBootApplicationForTheCurrentTest {

        record OurDomainModel(
                String name,
                int age
        ) {
        }

        @Component
        static class OurService {
            private final RestTemplate restTemplate;

            @Autowired
            public OurService(RestTemplateBuilder builder) {
                this.restTemplate = builder.build();
            }

            /**
             * You might be wondering why we make a call to the "real API" endpoint and how exactly it
             * is possible to imitate the real API endpoint in the test.
             */
            public OurDomainModel getOurDomainModel() {
                return restTemplate.getForObject(
                        "https://api.example.com/v1/some-endpoint",
                        OurDomainModel.class
                );
            }

            public OurDomainModel postOurDomainModel(
                    OurDomainModel request
            ) {
                return restTemplate.postForObject(
                        "https://api.example.com/v1/some-endpoint",
                        request,
                        OurDomainModel.class
                );
            }
        }
    }

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * This is our server-side component that we are going to test.
     */
    @Autowired
    private SpringBootApplicationForTheCurrentTest.OurService underTest;

    /**
     * This is the mock server that comes out of the box with Spring Boot.
     */
    @Autowired
    private MockRestServiceServer mockServer;
}

```

Now let's take a look at the test that verifies the behavior of the `OurService` class:

```
    @Test
    void getDomainModel_happyPath() {
        mockServer.expect(
                        MockRestRequestMatchers.requestTo("https://api.example.com/v1/some-endpoint")
                )
                .andExpect(
                        MockRestRequestMatchers.header("Accept",
                                org.hamcrest.CoreMatchers.containsString(MediaType.APPLICATION_JSON_VALUE)
                        )
                )
                .andRespond(withSuccess(
                        "{\"name\":\"John\",\"age\":25}",
                        MediaType.APPLICATION_JSON
                ));


        var response = underTest.getOurDomainModel();

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("John");
        assertThat(response.age()).isEqualTo(25);

        server.verify();
    }
```

One of the benefits of using `MockRestServiceServer` is that it allows you to verify that the request was made
to a "real" endpoint, while all the previous examples with `MockServer` were limited to making calls to localhost.
And not imagine a situation when you need to write an app-level test for the following use case:
-- user specifies a URL in the UI from which the app should fetch some data
-- service layer of the application verifies that the URL is valid, that it's public (and not a private/localhost IP address)
-- and then some data is fetched from the URL, transformed/stored/validated and returned to the UI

When using `MockServer` for such a test, you would have to make some tricks to make it work, like using remote mock server
or somehow intercepting the request and forwarding it to localhost. But with `MockRestServiceServer`, you can 
specify the real URL and it will work. However, you need to keep in mind that this is not a 100% "end to end" test,
since `MockRestServiceServer` does not make a real HTTP call (and we'll get to it later).

Here is another example of making a call with a request body. I also suggest that you pay attention to the matchers
api that is used by `MockRestServiceServer` - it's very powerful and flexible, and in my opinion, gives you more
freedom in comparison with `MockServer`'s api.

```
    @Test
    void postDomainModel_happyPath() {
        mockServer.expect(
                        MockRestRequestMatchers.requestTo("https://api.example.com/v1/some-endpoint")
                )
                .andExpect(
                        MockRestRequestMatchers.header("Content-Type",
                                org.hamcrest.CoreMatchers.containsString(MediaType.APPLICATION_JSON_VALUE)
                        )
                )
                .andRespond(withSuccess(
                        "{\"name\":\"John\",\"age\":25}",
                        MediaType.APPLICATION_JSON
                ));

        var response = underTest.postOurDomainModel(new SpringBootApplicationForTheCurrentTest.OurDomainModel("John", 25));

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("John");
        assertThat(response.age()).isEqualTo(25);

        server.verify();
    }
```

Also, the default behavior of `RestTemplate` is to throw an exception if the response status code is not 2xx - and
spring-web defines a whole family of exceptions for different status codes. This means that you usually don't have to
check status code manually: exceptional cases will result in an exception being thrown

```
    @Test
    void getDomainModel_404ThrowsException() {
        mockServer.expect(
                        MockRestRequestMatchers.requestTo("https://api.example.com/v1/some-endpoint")
                )
                .andRespond(MockRestResponseCreators.withResourceNotFound()
                        .body("Not found"));

        assertThatThrownBy(() -> underTest.getOurDomainModel())
                .withFailMessage("404 Not Found: [Not found]")
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
    }
```

## Short conclusion of Part 2

Spring ecosystem is very mature and provides lots of solutions for accessing external systems easier. 
If you check the source of any Spring Project (like Spring Security), you will see that they themselves rely on 
these solutions. And as a good boyscout, Spring also provides a built-in test-kit that you can use instead of 
re-inventing the wheel.