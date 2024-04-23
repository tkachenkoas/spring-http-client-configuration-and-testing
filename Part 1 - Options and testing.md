# Making HTTP calls in projects using Spring ecosystem

## Introduction

The majority of modern applications make HTTP calls to other systems. In this series of
articles, I want to share my thoughts on what should be the approach of
choosing the library to make HTTP calls in your Spring Boot-based projects.

Here are the links to the articles on medium:
- [Part 1 - Options and testing](...) (you're here)
- [Part 2 - Spring web client and test-kit](...)
- [Part 3 - ClientHttpRequestFactory as abstraction over http layer](...)
- [Part 4 - Configuring Apache Client via HttpComponentsClientHttpRequestFactory](...)
- [Part 5 - Setting hard limit on a request and failing fast](...)
- [Part 6 - Adding observability for web client](...)
All source code is available in the [GitHub repository](https://github.com/tkachenkoas/spring-web-client-in-depth).

Specifically, I am targeting the case of a big project with multiple teams
and multiple services. In such a case, it is important to have a consistent
approach to common tasks like making HTTP calls so that the code is straightforward to
understand and maintain by all the teams.

In this article, I will cover high-level topics (but with a few small dive-ins) and provide examples
for various approaches to making HTTP calls in Java, and how to test them.

I will not be covering the topic of reactive/async clients, as it's a separate topic. Also, most applications
still use synchronous clients (and other blocking processing). And as Java 21 is already out, it might be that
virtual threads will be a game-changer for choosing sync vs. async clients.

## Agenda

- Options for making HTTP calls in Java
  - Code examples for each option
  - Testing performed HTTP calls
- Spring's interfaces for making HTTP calls
  - RestTemplate, RestClient, `***Template` and `***Operations` patterns 
  - Spring's test kit for RestTemplate / RestClient
- What's under the facade of `RestTemplate`
  - `ClientHttpRequestFactory` abstractions
  - How `ClientHttpRequestFactory` is used in Spring's mocks/test-kits
- Configuring Apache Client as an implementation of `ClientHttpRequestFactory`
  - Connection pooling
  - Timeouts
- Adding observability for http client
  - Monitoring connection pool
  - Monitoring core metrics of outgoing requests
  - Logging via interceptors
- A deeper glance into non-happy-path scenarios:
  - handling unexpected network behavior
  - setting hard timeouts, failing fast

## Part 1 - Options and testing

Java has a number of libraries to make HTTP calls. Here are some of them:

- `java.net.HttpURLConnection` - the standard JDK means for making HTTP calls.
  it's unlikely that you will use it in a modern application, but some legacy
  applications might still use it.
- Java 11 HttpClient — a new addition to the JDK. It's a modern and flexible
  solution
- Apache HttpClient (```org.apache.httpcomponents.client5:httpclient5```) - a popular library that has been around for a
  long time
- OkHttp (```com.squareup.okhttp3:okhttp```) - a modern and efficient library
- Jersey (```org.glassfish.jersey.core:jersey-client```) - a JAX-RS implementation of http client
- Retrofit (```com.squareup.retrofit2:retrofit```) - a type-safe HTTP client for Android and Java
- ... and some others

Further, I'll show quick code examples for each of the libraries.

Also, spring-web module provides a `RestTemplate` class that can be used to make HTTP calls with convenient "template"
methods. Most of the article will be dedicated to Spring's built-in api for making HTTP calls.

## Testing making HTTP calls

No matter which library you choose, it's important to test that it works as expected
(and also non-happy-path scenarios). 

First, a crucial rule how NOT to test HTTP calls:
```
DO NOT USE MOCKITO TO MOCK INVOCATIONS OF HTTP CLIENTS
```
Few reasons for it:
- It's generally not a good idea to mock something you don't own. Libraries/components that are designed to be 
used by other developers should either be tested in integration with FULL real implementation and infrastructure, 
or should provide mock-implementations that are compatible with the real one in most cases.  
- It's hard to maintain such tests: in many cases, you can do a legal refactoring that will break the 
tests because of the way how the mocks are set up to expect specific method with specific arguments
(e.g. if api has multiple ways to achieve the same result, you might need to change the test to reflect the change)
- such tests are very limited in exploring the real behavior of the system. 
  - as a quick example, when you bypass the real deserialization logic, bugs in production may happen because you forgot to
  add no-arg constructor to the model class, or forgot to add annotation for naming strategy, etc.
  - also, exploring non- 2xx responses may become a guessing game: do I need to check status code, or does the client
  throw an exception (and what kind of exception)?
- some behavior is just too painful to mock via Mockito (like content-type that comes from annotations)

A good way to test http calls is to use a mock server. Two popular options are `WireMock`
and `MockServer`, in this article, I'll use ```https://www.mock-server.com/```

This is a very convenient library that also integrates with JUnit 5 to reduce the boilerplate code.

```
// https://mvnrepository.com/artifact/org.mock-server/mockserver-netty
testImplementation 'org.mock-server:mockserver-junit-jupiter:5.15.0'
```

And here is the example of base-test class that is used in the examples below:

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

Jersey is a JAX-RS implementation, so it's very convenient to use if you are already using JAX-RS for web layer 
(and not using Spring-Web). Also, unlike the previous examples, it has a built-in support for parsing the response body 
into an object, which is very convenient.

Also, it's worth mentioning that Jersey has proxy-extension ```implementation 'org.glassfish.jersey.ext:jersey-proxy-client'```
for creating a proxy for an interface that represents the API. Here is an example of how to use it:

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

## Summary and thoughts on choosing the library

No matter which library you choose (except for direct usage of ```java.net.HttpURLConnection``` :) ), you can achieve the 
expected behavior of your system. Some libraries have more complicated features and apis, some are more lightweight, but 
for the simple cases, they all work well. Today the internet is full of examples and tutorials for each of 
the libraries, so you will find something that matches your case. Also, many developers, use AI tools (or at least 
ChatGPT)— it's not a big deal to achieve your goal with any of the libraries.

However, there are some things to consider when choosing the solution:

- whether one line of your action in human-readable language is represented by one line of code in the library
- whether the library is actively maintained (all the mentioned libraries are, but it's not always the case)
- whether it supports serialization/deserialization of the request/response bodies as part of the api
- whether it has additional test-kit features to make testing easier
- whether it supports interface/"typed" proxy apis