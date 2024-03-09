# Making HTTP calls in projects using Spring ecosystem

## Introduction

The majority of modern applications make HTTP calls to other systems. In this
article, I want to share my thoughts on what should be the approach of
choosing the library to make HTTP calls in your Spring Boot-based projects.

Specifically, I am targeting the case of a big project with multiple teams
and multiple services. In such a case, it is important to have a consistent
approach to common tasks like making HTTP calls so that the code is easy to
understand and maintain by all the teams.

In this article, I will cover high-level topics (but with few small dive-ins) and provide examples
for various approaches to making HTTP calls in Java, and how to test them. 

I will not be covering the topic of reactive/async clients, as it's a separate topic. Also, because most applications
still use synchronous clients (and other blocking processing). And as Java 21 is already out, might be that 
virtual threads will be a game-changer for choosing sync vs async clients.

## Agenda

- Options for making HTTP calls in Java
- Code examples for each option
- Testing making HTTP calls
- Spring's RestTemplate and RestClient
- Spring's test kit for RestTemplate / RestClient
- What's under the facade of `RestTemplate`
- The beauty of `RestTemplate` and `ClientHttpRequestFactory` abstractions
- Configuring an implementation of `ClientHttpRequestFactory` with apache http client
- Conclusion

## Options for making HTTP calls in Java

Java has a number of libraries to make HTTP calls. Here are some of them:

- `java.net.HttpURLConnection` - the standard JDK means for making HTTP calls.
  it's unlikely that you will use it in a modern application, but some legacy
  applications might still use it.
- Java 11 HttpClient - a new addition to the JDK. It's a modern and flexible
  solution
- Apache HttpClient (```org.apache.httpcomponents.client5:httpclient5```) - a popular library that has been around for a
  long time
- OkHttp (```com.squareup.okhttp3:okhttp```) - a modern and efficient library
- Jersey (```org.glassfish.jersey.core:jersey-client```) - a JAX-RS implementation of http client
- Retrofit (```com.squareup.retrofit2:retrofit```) - a type-safe HTTP client for Android and Java
- ... and some others

Further, I'll show quick code examples for each of the libraries.

Also, spring-web module provides a `RestTemplate` class that can be used to make HTTP calls with convenient "template"
methods.
And I'll dedicate a separate section to it.

## Testing making HTTP calls

No matter which library you choose, it's important to test that it works as expected
(and also non-happy-path scenarios). One way to do it is to use a mock server. Two popular options are `WireMock`
and `MockServer`,
in this article, I'll use ```https://www.mock-server.com/```

This is a very convenient library that also integrates with JUnit 5 to reduce the boilerplate code.

```
// https://mvnrepository.com/artifact/org.mock-server/mockserver-netty
testImplementation 'org.mock-server:mockserver-junit-jupiter:5.15.0'
```

And here is the example of base-test class that is used in the examples:

```
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.mockserver.client.MockServerClient;
import org.mockserver.junit.jupiter.MockServerSettings;

@MockServerSettings(ports = 1090)
@RequiredArgsConstructor
public class TestWithMockServer {

    protected final MockServerClient mockServer;

    protected record SampleResponseModel(String name, int age) {
    }

    @BeforeEach
    void resetAndConfigureSimpleCall() {
        mockServer.reset();
        mockServer.when(
                org.mockserver.model.HttpRequest.request()
                        .withMethod("GET")
                        .withPath("/some-endpoint")
        ).respond(
                org.mockserver.model.HttpResponse.response()
                        .withStatusCode(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"John\",\"age\":25}")
        );
    }

}
```

General explanation of the setup:

- `@MockServerSettings(ports = 1090)` - will start mock server (via ```MockServerExtension``` that is used under the
  hood) on port 1090
- `@RequiredArgsConstructor` + ```protected final MockServerClient mockServer``` - will inject the mock server client
  into the test class
- `@BeforeEach void reset()` - will reset the mock server before each test
- `mockServer.when(...).respond(...)` - will set up the expectation for the mock server for a simple call
- each test might also add additional expectations when needed

## Code examples for each option

### java.net.HttpURLConnection

```
    @Test
    void goodOldDirectViaJavaConnection() throws Exception {

        URL url = new URL("http://localhost:1090/some-endpoint");
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.connect();
        int responseCode = connection.getResponseCode();
        assertThat(responseCode).isEqualTo(200);

        String responseBody = new String(connection.getInputStream().readAllBytes());
        connection.disconnect();

        var asObject = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(responseBody, SampleResponseModel.class);

        assertThat(asObject).isEqualTo(new SampleResponseModel("John", 25));
    }
```

Not that we have to manually parse the response body into an object by using any JSON library (in this case, Jackson).

### Java 11 HttpClient

```
    @Test
    void viaJava11HttpClient() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:1090/some-endpoint"))
                .header("Accept", "application/json")
                .build();
        HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        var asObject = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(response.body(), SampleResponseModel.class);

        assertThat(asObject).isEqualTo(new SampleResponseModel("John", 25));
    }
```

In comparison with `java.net.HttpURLConnection`, the `java.net.http.HttpClient` requires less boilerplate code.

### Apache HttpClient

```
    @Test
    void withApacheHttpClient() throws Exception {
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            HttpGet request = new org.apache.hc.client5.http.classic.methods.HttpGet("http://localhost:1090/some-endpoint");
            request.addHeader("Accept", "application/json");
            CloseableHttpResponse response = client.execute(request);
            assertThat(response.getCode()).isEqualTo(200);

            var asObject = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response.getEntity().getContent(), SampleResponseModel.class);

            assertThat(asObject).isEqualTo(new SampleResponseModel("John", 25));
        }
    }
```

I will not dive deeper into the Apache HttpClient, as it's a big library with a lot of features, but
you can see that it adds more abstractions over the HTTP protocol (
like `CloseableHttpClient`, `HttpGet`, `CloseableHttpResponse`).

### OkHttp

```
    @Test
    void withOkHttp() throws Exception {
        var client = new OkHttpClient();
        var request = new Request.Builder()
                .url("http://localhost:1090/some-endpoint")
                .addHeader("Accept", "application/json")
                .build();
        Response response = client.newCall(request).execute();
        assertThat(response.code()).isEqualTo(200);

        var asObject = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(response.body().string(), SampleResponseModel.class);

        assertThat(asObject).isEqualTo(new SampleResponseModel("John", 25));
    }
```

As you can see, OkHttp is very similar to Java 11 HttpClient and Apache HttpClient in terms of the amount of boilerplate
code.

### Jersey

```
    @Test
    void withJerseyClient() {
        try (var client = jakarta.ws.rs.client.ClientBuilder.newClient()) {
            SampleResponseModel response = client.target("http://localhost:1090/some-endpoint")
                    .request()
                    .get(SampleResponseModel.class);

            assertThat(response).isEqualTo(new SampleResponseModel("John", 25));
        }
    }
```

Jersey is a JAX-RS implementation, so it's very convenient to use if you are already using JAX-RS in your project.
Also, unlike the previous examples, it has a built-in support for parsing the response body into an object, which is
very convenient.

Also, it's worth mentioning that Jersey has proxy-extension ```implementation 'org.glassfish.jersey.ext:jersey-proxy-client'```
for creating a proxy for an interface that represents the  API. Here is an example of how to use it:

```
    @Test
    void withJerseyClient_viaApiInterfaceProxy() {

        @Produces("application/json")
        @Consumes("application/json")
        interface RemoteApi {
            @GET
            @Path("/some-endpoint")
            SampleResponseModel getOurDomainModel();
        }

        var target = jakarta.ws.rs.client.ClientBuilder.newClient().target("http://localhost:1090");

        var ourServiceProxy = WebResourceFactory.newResource(RemoteApi.class, target);

        SampleResponseModel response = ourServiceProxy.getOurDomainModel();

        assertThat(response).isEqualTo(new SampleResponseModel("John", 25));
    }
```

### Retrofit

```
    @Test
    void withRetrofit() throws Exception {
        interface RemoteRetrofitService {
            @retrofit2.http.GET("/some-endpoint")
            retrofit2.Call<SampleResponseModel> getOurDomainModel();
        }
        
        retrofit2.Retrofit retrofit = new retrofit2.Retrofit.Builder()
                .baseUrl("http://localhost:1090")
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        RemoteRetrofitService service = retrofit.create(RemoteRetrofitService.class);
        retrofit2.Call<SampleResponseModel> call = service.getOurDomainModel();
        retrofit2.Response<SampleResponseModel> response = call.execute();
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(new SampleResponseModel("John", 25));
    }
```

Similar to Jersey, Retrofit has a type-safe HTTP client, which means that it creates a type-safe proxy for the API that you define.
As you can see, it's very convenient to use, but it also requires some additional setup 
(like defining the interface and the `Retrofit` object which acts as a factory for creating the actual HTTP client).

### Some summary and thoughts

No matter which library you choose, you will most probably achieve the same result for your cases. Some libraries
have more complicated features and apis, some are more lightweight, but for the simple cases, they all work well.
Given that today the internet is full of examples and tutorials for each of the libraries, and also that
most of us, developers, use AI tools (or at least ChatGPT), it's not a big deal to achieve your goal with any of the
libraries.

However, there are some things to consider when choosing the solution:

- whether one line of your action is represented by one line of code in the library
- whether the library is actively maintained (all of mentioned libraries are, but it's not always the case)
- whether it supports serialization/deserialization of the request/response bodies as part of the api
- whether it has additional test-kit features to make testing easier
- whether it supports interface/"typed" proxy apis 
- ... what else?

## Spring's RestTemplate and RestClient

### The `Template` and `Operations` pattern in Spring

If you are using Spring for web, db, and other things, you might have noticed that Spring has a lot of classes
named `SomethingTemplate` or `SomethingOperations`. For example, `JdbcTemplate`, `MongoTemplate`, `RestTemplate`,
`TransactionTemplate` (and their `SomethingOperations` interfaces). This is a pattern that Spring uses to provide
a convenient way to work with some external system (like a database, a web service, etc). Generally, the `Template`
classes are stateless and thread-safe, and they provide a very convenient way to perform common operations, but also
support customization (via interceptors, callbacks, event listeners, etc). Another important benefit of using
the `Template` classes is that they throw Spring's exceptions no matter what the underlying implementation is.

Usually, the `Template` classes are built on top of some "native" client (like `java.net.HttpURLConnection`,
mongo-java-driver, jedis etc),
instead of providing a new implementation from scratch. This means that you can always use the "native" client if you
need -
but you will lose the convenience and the consistency that the `Template` provides. In my opinion, whenever you need to
work with
some external system, you should first check if Spring has a `Template` for it, and only then consider using a "native"
client.

### But RestTemplate is deprecated! Don't you know how to google?

Well, as of the date of writing this article, `RestTemplate` is not deprecated. It's true that Spring has introduced a
new
interface `RestClient` with synchronous and asynchronous implementations, and it's true that Spring
suggests using it instead of `RestTemplate`.

This is written in the documentation of `RestTemplate`:

```
     NOTE: As of 6.1, RestClient offers a more modern API for synchronous HTTP access.
     For asynchronous and streaming scenarios, consider the reactive
     org.springframework.web.reactive.function.client.WebClient.
```

So essentially, the `RestClient` is just another interface that provides the same functionality as `RestTemplate`,
but when used with webflux, it also provides a non-blocking api. But if you are not using webflux, you can still
use `RestTemplate`
and will be just fine.

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
into an object.
In general, it's not recommended to use `new RestTemplate()` (and to make additional configs), but I'll get to this
later.

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
parameters.
As you can see, this api satisfies the criteria
of ```one line of your action is represented by one line of code in the library```.

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

### Ok, and what about `RestClient`?

An interesting thing about `RestClient` is that it can be built on top of `RestTemplate` (since under the hood the
sync implementation of `RestClient` uses same abstractions as `RestTemplate`). This means that you can use `RestClient`'
s
newer fluent api, but still use `RestTemplate` that was preconfigured in your project. Here is an example of how to do
it:

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

As you can see, the `RestClient`'s api is more fluent and convenient to use, but it's also sometimes more verbose.


And of course, Spring provides a built-in support for creating a proxy for an interface that represents the API. Here is
an example:

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

### A better alternative to type-safe proxy for the API

The interface-based proxy that is available in `Retorfit`, `Jersey` and `RestClient` is a very convenient way to
work with the API. However, it's not always the best way to do it. Lots of APIs are documented with OpenAPI/Swagger,
and there are tools that can generate the client code for you.

Some time ago I wrote an article about how to use `openapi-generator` to generate the client code for testing your
server-side code. You can find it here: https://medium.com/duda/cleaner-spring-boot-it-rest-tests-with-client-generation-cc3ac880d9ec

Main benefit of using the generated client code is zero manually written code. Components that you will use will be 
generated from the API definition that server-side team provides for you. This means that you can replace tests with 
mock-server with tests on Mockito mocks, or even subclassed mock-reimplementations to support integration cases.

If you are the one who is responsible for the API, you should also consider investing time into properly documenting 
the API so that its consumers can generate the client code for it in any language they want. Also, if you store the
generated schema in the version control system, you can see the changes in the API and will make sure that the schema
doesn't introduce accidental breaking changes to consumers that use it for generating the client code.

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
since I have not configured any mandatory properties for them (like database driver, url, etc), the test would fail.

However, if I use `@RestClientTest`, only the auto-configuration for the rest clients will be loaded, 
and as free additional benefit, `MockRestServiceServer` will be auto-configured for me. Be careful, though,
because Spring will try to initialize all your beans that are in scope of `@SpringBootApplication` class,
thus typically you would want to use `@ContextConfiguration` to limit the scope of the context to the class under test.

```
@RestClientTest
public class CCC_TestingWithRestClientTest {

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
And not imagine a situation when you need to write an app-level test for a following use case: 
-- user specifies a URL in the UI from which the app should fetch some data
-- service layer of the application verifies that the URL is valid, that it's public (and not a private/localhost IP address)
-- and then some data is fetched from the URL, transformed/stored/validated and returned to the UI

When using `MockServer` for such a test, you would have to make some tricks to make it work, like using remote mock server
or somehow intercepting the request and forwarding it to localhost. But with `MockRestServiceServer`, you can just
specify the real URL and it will work. However, you need to keep in mind that this is not a 100% "end to end" test,
since `MockRestServiceServer` does not actually make a real HTTP call (and we'll get to it later).

Here is another example of making a call with a request body. I also suggest that you pay attention to the matchers
api that is used by `MockRestServiceServer` - it's very powerful and flexible, and in my opinion gives you more
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
## What's under the facade of `RestTemplate`

As I mentioned earlier, `RestTemplate` does not actually have its own implementation of the HTTP client. Instead,
it uses the `ClientHttpRequestFactory` abstraction to delegate the actual HTTP calls to some "native" client.

And the behavior of `MockRestServiceServer` is based on the `MockClientHttpRequestFactory` that is set to the
rest template instead of the "real" `ClientHttpRequestFactory` that will make the actual HTTP calls.

```
    @Test
    void aGlimpseIntoMockRestServiceServer() {
        RestTemplate restTemplate = new RestTemplate();

        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);

        Assertions.assertThat(restTemplate.getRequestFactory().getClass().getSimpleName())
                /**
                 * @{@link org.springframework.test.web.client.MockRestServiceServer#MockClientHttpRequestFactory}
                 * // no actual HTTP request is made, so we do whatever we want
                 * with expected "requests" and "responses"
                 */
                .isEqualTo("MockClientHttpRequestFactory");
    }
```

Here is some visual comparison of what making a call to `MockServer` (or another server) looks in comparison with `MockRestServiceServer`:

- https://i.imgflip.com/8gm1t9.jpg
- https://i.imgflip.com/8gm2io.jpg

In many examples in the internet, you will see the ```new RestTemplate()``` being used, and I do not recommend doing
it (unless you are writing a small script or a prototype). And one the reasons is that default request factory of
`RestTemplate` is `SimpleClientHttpRequestFactory` that is a bit limited

```
    @Test
    void theDefaultRequestFactoryOfRestTemplateBehavior() {
        RestTemplate restTemplate = new RestTemplate();

        Assertions.assertThat(restTemplate.getRequestFactory())
                .isInstanceOf(org.springframework.http.client.SimpleClientHttpRequestFactory.class);

        // try and guess what will happen here
        restTemplate.patchForObject("https://api.example.com/v1/some-endpoint", null, String.class);
    }
```

## The beauty of `RestTemplate` and `ClientHttpRequestFactory` abstractions

Since `RestTemplate` and `ClientHttpRequestFactory` are 'natural' Spring abstractions, you can even integrate
them in testing your server-side code. I'm pretty sure that you are familiar with the `@WebMvcTest` and `MockMvc`
that allows you to test your controllers without starting the whole application context and underlying servers. But
you might have noticed that `MockMvc` is not very convenient to use when you need just make a simple HTTP call:
```
 mockMvc.perform(
                MockMvcRequestBuilders.get("/some-endpoint")
                        .accept(MediaType.APPLICATION_JSON)
        )
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().json("{\"name\":\"John\",\"age\":25}"));
```
Also, since we delegate assertions to `MockMvcResultMatchers`, we lose convenience of debugging that we have when
we use `assertThat` from `org.assertj.core.api.Assertions` on the response body.

Another example when you need to make a call with a request body and then read the response body into some class:
```
  record ResApiResponse(String name) {
  }
  
  RestApiResponse response = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(
                        mockMvc.perform(
                                MockMvcRequestBuilders.post("/some-endpoint")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"name\":\"John\"}")
                        )
                                .andExpect(MockMvcResultMatchers.status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        ResApiResponse.class
                );   
```

And now multiply this by the number of endpoints/cases that you usually need to test. And sad fact is that
many developers also do not know about `TestRestTemplate` / `WebTestClient` (but it's a different story).

But good news is that you can use `RestTemplate` / `RestClient` as a client to make calls to your MockMvc-covered 
server-side code in the same way as you make calls to external services. 

The trick is to define a `RestTemplate` bean in your test configuration that uses `MockMvcClientHttpRequestFactory`
and the `MockMvc` instance that you get from the context
```
      @Bean
      RestOperations mockMvcRestOperations(MockMvc mockMvc) {
          RestTemplate restTemplate = new RestTemplate();
          restTemplate.setRequestFactory(new MockMvcClientHttpRequestFactory(mockMvc));
          return restTemplate;
      }
```

The benefit of this approach will show itself when you will need to debug an environment-sensitive production issue that is covered by 
a long cross-endpoint behavioral unit test that is green. You can just adjust the configuration of the `RestTemplate` bean to use the "real" http client,
do some tricks with api authentication - and voila, a replayable cross-endpoint test-case is ready in a few minutes.

```

@WebMvcTest
public class EEE_AnotherRequestFactory {

    @SpringBootApplication
    static class TheSpringBootApplication {

        record ResponseModel(String name, int age) {
        }

        @RestController
        static class TheRestController {
            @GetMapping("/api/v1/some-endpoint/{name}/{age}")
            public ResponseModel getResponse() {
                return new ResponseModel("John", 25);
            }

        }

        @Bean
        RestOperations mockMvcRestOperations(MockMvc mockMvc) {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.setRequestFactory(new MockMvcClientHttpRequestFactory(mockMvc));
            return restTemplate;
        }
    }

    @Autowired
    RestOperations restOperations;

    @Test
    void callOurGetEndpoint_shouldReturnOurResponse() {

        record ClientSideResponse(String name, int age) {
        }

        var response = restOperations.getForObject(
                "/api/v1/some-endpoint/{name}/{age}",
                ClientSideResponse.class,
                "John", 25
        );
        assertThat(response.name()).isEqualTo("John");
        assertThat(response.age()).isEqualTo(25);

        // the rest template can also be tweaked not to throw exceptions
        // when 4xx or 5xx status codes are returned

        var restTemplate = (RestTemplate) restOperations;

        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            protected void handleError(ClientHttpResponse response, HttpStatusCode statusCode) {
                // we won't throw exceptions here, and instead we will let the flow handle the response status
            }
        });

        // now we can make a request to a non-existing endpoint
        var fourOhFour = restOperations.getForEntity(
                "/api/v1/another-endpoint/{name}/{age}",
                ClientSideResponse.class,
                "John", 25
        );
        assertThat(fourOhFour.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fourOhFour.getBody()).isNull();
    }

}

```

## Configuring an implementation of `ClientHttpRequestFactory` with apache http client

In production, our application will be making calls to external services via HTTP, at let's take a look at how to
properly configure `RestTemplate` to use Apache HttpClient as the underlying HTTP client. Typical reasons for doing
this are:
- reusing connections instead of creating a new connection for each request - to reduce TSL overhead
- monitoring that connection pools is not exhausted and that single routes are not stealing all the connections
- making sure that slow requests do not block the application indefinitely

First, let's take a short look at setting and testing timeout via `SimpleClientHttpRequestFactory`. As you can see,
if the remote server is not responding in a reasonable time, we'll fail fast and not wait for all the time in the universe.

```
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
```

And here is an example of configuring `HttpComponentsClientHttpRequestFactory` to use Apache HttpClient with 
connection pooling and reusing connections. In the provided example, we are making 20 concurrent requests to the
same server, and we have only 10 connections in the pool. This means that the second half of the requests will have
to wait, and if you want bigger throughput, you should increase the pool size. You can use your favorite monitoring
tool to see the connection pool stats if you will use `PoolingHttpClientConnectionManagerMetricsBinder` to report
the stats to your `MeterRegistry`

```
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

Finally, here is an example of how to configure a minimalistic `RestTemplate` with a sane set of configurations
for production use. This is a good starting point for your `RestTemplate` configuration, and you can adjust it
to your needs. You can also use `RestTemplateBuilder` to build a `RestTemplate` with the same configuration.

```
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
```

## Conclusion

In this article, I've shown you to use `RestTemplate` and `RestClient` to make HTTP calls to external services.
I've also shown you how to test the code that uses `RestTemplate` and `RestClient` with `MockRestServiceServer` and
`@RestClientTest`. I've also shown you how to use `RestTemplate` as a client to make calls to your `@WebMvcTest`-covered
in same way as you would make calls to external services.

Core points of the article are:
- if you are using `Spring`, you should use `RestTemplate` or `RestClient` to make HTTP calls to external services
- you will get very simple and/or powerful apis out of the box without bringing 10 different libraries to your project
- use built-in `MockRestServiceServer` and 'sliced' `@RestClientTest` to unit-test your code that makes HTTP calls, but
keep in mind that it's a mock that does not actually make HTTP calls
- configure underlying `ClientHttpRequestFactory` not to get into trouble with timeouts, connection pools, and other
things that are important for production use
- use `MockServer` to test/introspect e2e behavior of http calls in your application