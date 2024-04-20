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

I will not be covering the topic of reactive/async clients, as it's a separate topic. Also, most applications
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

## Deeper exploring of non-happy-path scenarios with timeouts

In the previous examples, we've seen how to test the happy path of the code that makes HTTP calls. But what if 
thing will go really wrong, and our application relies on making HTTP calls to external services a lot? An example
of "wrong" can not even be programming errors, but also network issues that result in very slow bandwidth, high latency
and all the other things that can happen in the real world.

Let's build a simple setup that will give us some control over behavior that is configured in http client. E.g.,
we want to be able to deeper mock "the other side" (unfortunately, ```MockServer``` doesn't provide full flexibility here): 
- ```requestTimeout``` -- the time for server to start responding to the request  
- ```socketTimeout``` -- the time between packets of data being received

Following setup allows us to return the response with a delay, and also to pause between sending slices of the response.
Keep in mind that this is just a very limited simulation, later in the article I'll show you a much 
better tool for this purpose.

```
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
                for (int i = 0; i < slices.size(); i++) {
                    Thread.sleep(pause);
                    var nextLine = slices.get(i);
                    log.debug("Sending: {}", nextLine);
                    writer.println(nextLine);
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
    
```

This is how our RestTemplate configuration will look like. Essentially, we're setting the timeouts to 1 second
into everything, because we expect all the requests to be fast.

```
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
```

First, I'd like to clarify how `socketTimeout` and `responseTimeout` are related to each other in integration.
The `socketTimeout` is the configuration of connection pool manager, and `responseTimeout` is the configuration
of the internal client instance. If set, response timeout will override the socket timeout. This means that 
`responseTimeout` is optional, but it defines the actual maximum wait time between bytes of data being received.

```
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
```

Now, let's imagine that delay and pause are just below our timeout, and as you can see, we will get full response, 
but it will take much more time than we expect.

```
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
```

The controller that we've built is a "kindergarten" version that allows us to simulate some of the network issues.
In real world, you would expect to check how your real application behaves under different network conditions, so 
having such controllers is not an option. Instead, you can the tool called
[Toxiproxy](https://github.com/Shopify/toxiproxy) that allows you to simulate network issues in a very flexible way.

It also comes as a [TestContainer](https://java.testcontainers.org/modules/toxiproxy/), so you can easily start using
it. The documentation for setup and usage is very good, and provides multiple examples. I will just show very basic
usage of it.

Here is sample code that allows toxiproxy container to call our upstream server running on localhost - and
the ```ToxiProxySetup``` record will contain all the information for us to make calls to 
our server via toxiproxy.

```
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
                "sliced-response", "0.0.0.0:8666",
                "host.testcontainers.internal:" + serverPort
        );
        String proxyHost = toxiproxy.getHost();
        int proxyPort = toxiproxy.getMappedPort(8666);
        log.info("Toxiproxy port: {} & IP: {}", proxyPort, proxyHost);

        return new ToxiProxySetup(proxyHost, proxyPort, proxy);
    }
```

And now let's execute the same case of receiving sliced response, but now we will use toxiproxy to simulate the
delays. 

```
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
```

Now let's check out the toxirpoxy's "poison" that allows us to simulate slow bandwidth. 
In this case, we will call the endpoint that returns 12kb of data, and we will limit the bandwidth to 2kb/s.

As you can see, the that we have are not enough to protect us from the slow network.

```
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
```

## Hard solution for slow requests

The previous examples show us that none of the timeouts that we set on the `RestTemplate` via underlying `Apache HttpClient` 
are enough to limit full request execution time. In most cases API calls are expected to be fast, and if they are not,
it can cause issues in the application and can affect the user experience. Quite often, it's better to fail (and show 
a message that service is not available) than to wait for a long time and then still fail on some gateway timeout.

Unfortunately, `Apache HttpClient` does not provide a way to set a hard timeout on the request execution directly,
but it provides apis to cancel the request execution. However, since the "native" client 
is hidden behind the abstractions of `spring-web`(`RestTemplate` and `ClientHttpRequest`), and it's not easy to access the 
`HttpGet/HttpPost` instance and cancel the request. It requires some reflection magic, and looks a bit dangerous.

In this example, we wrap the `ClientHttpRequestFactory` that is used by `RestTemplate` and schedule the canceling
of the request after a certain time. This way, we can set a hard timeout on the request execution.

```
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
    
```

You might hear some arguments that using reactive approach with async http client is a good way to handle long calls, 
but in my opinion, it's not always the case.
- there might be a live user on the other side of the request that can't wait for a long time
- if you know that your requests are expected to be fast, long calls are a sign of a problem
on the server side. Imagine that eventually all the thousands of async requests will be processed, 
and you will need to do something with the result (e.g. load response body into memory, 
save to the database, call another service). Will everything go well in this case?

E.g., if you have a service that is expected to respond in 100ms, and it starts to respond in 10 seconds,
making additional calls (even in nice async manner) will not make the target service any good. The typical pattern 
in distributed systems is to stop calling the service that misbehaves until it recovers, and its is called "circuit breaker".

Java has a library called `resilience4j` that provides implementations of many patterns for building resilient applications, 
and it provides an amazing implementation of the circuit breaker [Resilience4j](https://resilience4j.readme.io/docs/circuitbreaker).
Thus, we can use the circuit breaker to extend our `RestTemplate` via `ClientHttpRequestInterceptor` to prevent
calls to degraded service.

Pay attention that `CircuitBreaker` has a very flexible and thus non-trivial configuration, and you should
test the behavior of your circuit breaker to make sure that it acts as you expect it to act. Else you can end up
having your circuit breaker in open state even when the initial problem is already resolved. Luckily, `resilience4j`
also provides a lot of extension point for monitoring the circuit breaker state and events.

```
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
```

## Conclusion

In this article, I've shown you to use `RestTemplate` and `RestClient` to make HTTP calls to external services.
I've also shown you how to test the code that uses `RestTemplate` and `RestClient` with `MockRestServiceServer` and
`@RestClientTest`. I've also shown you how to use `RestTemplate` as a client to make calls to your `@WebMvcTest`-covered
in same way as you would make calls to external services.

Core points of the article are:
- if you are using `Spring`, use `RestTemplate` or `RestClient` to make HTTP calls to external services as a facade for 
http client and configure it via the extension points that `RestTemplateBuilder` provides
- you will get very simple and/or powerful apis out of the box without bringing 10 different libraries to your project 
- use built-in `MockRestServiceServer` and 'sliced' `@RestClientTest` to unit-test your code that makes HTTP calls, but
keep in mind that it's a mock that does not actually make HTTP calls
- configure underlying `ClientHttpRequestFactory` not to get into trouble with timeouts, connection pools, and other
things that are important for production use
- use `MockServer` to test/introspect e2e behavior of http calls in your application
- use `ToxiProxy` to simulate tricky network conditions to test the configuration of your timeouts
- use `CircuitBreaker` to protect your application from slow/constantly failing services