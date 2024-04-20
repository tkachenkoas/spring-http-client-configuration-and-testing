# ClientHttpRequestFactory as abstraction over http layer

## Introduction
This article is a part of a series of articles about the deep dive into
making http calls in Spring. Here are the other parts of the series:

- [Part 1 - Options and testing](...)
- [Part 2 - Spring web client and test-kit](...)
- [Part 3 - ClientHttpRequestFactory as abstraction over http layer](...) (you're here)
- [Part 4 - Configuring Apache Client via HttpComponentsClientHttpRequestFactory](...)
- [Part 5 - Adding observability for web client](...)
- [Part 6 - A deeper glance into non-happy-path scenarios](...)
  All source code is available in the [GitHub repository](https://github.com/tkachenkoas/spring-web-client-in-depth).

## What's under the facade of `RestTemplate`

As I mentioned earlier, `RestTemplate` does not have its own implementation of the HTTP client. Instead,
it uses the `ClientHttpRequestFactory` abstraction to delegate the actual HTTP calls to some "native" client.

For instance, the behavior of `MockRestServiceServer` is based on the `MockClientHttpRequestFactory` that is set to the
rest template instead of the "real" `ClientHttpRequestFactory` that will make the actual HTTP calls.

```
    @Test
    void aGlimpseIntoMockRestServiceServer() {
        RestTemplate restTemplate = new RestTemplate();

        // This logic is executed as part of @RestClientTest bootstrapping.
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

Here is a visual comparison of what making a call to `MockServer` (or a remote server) looks in comparison with `MockRestServiceServer`:

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

Since `RestTemplate` and `ClientHttpRequestFactory` are 'natural' Spring-web abstractions, you can even integrate
them in testing your server-side code. I'm pretty sure that you are familiar with the `@WebMvcTest` and `MockMvc`
that allows you to test your controllers without starting the whole application context and underlying servers. But
you might have noticed that `MockMvc` is often quite verbose when you need to test a simple HTTP call:
```
 mockMvc.perform(
                MockMvcRequestBuilders.get("/some-endpoint")
                        .accept(MediaType.APPLICATION_JSON)
        )
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().json("{\"name\":\"John\",\"age\":25}"));
```
I bet that when writing any test from scratch, you always search for the class that provides all the `get(...)`,
`status()` static methods. Also, since we delegate assertions to `MockMvcResultMatchers`, we lose convenience of 
debugging that we have when we use `assertThat` from `org.assertj.core.api.Assertions` on the response.

Another example of `MockMvc` verbosity when you need to make a call with a request body and then read the response 
body into a POJO:
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

The benefit of this approach will show itself when you will need to debug an environment-sensitive production issue 
 covered by a long cross-endpoint behavioral unit test that is green. You can adjust the configuration 
of the `RestTemplate` bean to use the "real" http client, do some tricks with api authentication - and voilà, 
a cross-environment test-case is ready in a few minutes.

```
/**
 * Illustrates how to use {@link MockMvcClientHttpRequestFactory} to test the client-side
 * with RestTemplate, and not MockMvc.
 */
@WebMvcTest
public class Part03_02_ClientHttpRequestFactory {

    // this is the "inlined" application we're going to test
    @SpringBootApplication
    static class TheSpringBootApplication {

        record ResponseModel(String name, int age) {
        }

        // this is our server-side controller
        @RestController
        static class TheRestController {
            @GetMapping("/api/v1/some-endpoint/{name}/{age}")
            public ResponseModel getResponse(@PathVariable Integer age, @PathVariable String name) {
                return new ResponseModel(name, age);
            }

        }

        @Qualifier("mockMvcRestOperations")
        @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
        @Retention(RetentionPolicy.RUNTIME)
        @interface MockMvcRestTemplate {
        }

        @MockMvcRestTemplate
        @Bean
        RestOperations mockMvcRestOperations(MockMvc mockMvc) {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.setRequestFactory(new MockMvcClientHttpRequestFactory(mockMvc));
            return restTemplate;
        }

        // by default, RestTemplate throws exceptions when 4xx or 5xx status codes are returned
        // but when we know that we expect such status codes, we can tweak the RestTemplate
        // to not throw exceptions, so that to assert the response status ourselves
        @Qualifier("nonThrowingRestOperations")
        @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
        @Retention(RetentionPolicy.RUNTIME)
        @interface NonThrowingMockMvcRestTemplate {
        }

        @NonThrowingMockMvcRestTemplate
        @Bean
        RestOperations nonThrowingRestOperations(MockMvc mockMvc) {
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.setRequestFactory(new MockMvcClientHttpRequestFactory(mockMvc));
            restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
                @Override
                protected void handleError(@NotNull ClientHttpResponse response, @NotNull HttpStatusCode statusCode) {
                    // we won't throw exceptions here, and instead we will let the flow handle the response status
                }
            });
            return restTemplate;
        }
    }

    @MockMvcRestTemplate
    @Autowired
    RestOperations restOperations;

    @NonThrowingMockMvcRestTemplate
    @Autowired
    RestOperations nonThrowingRestOperations;

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

        assertThatExceptionOfType(HttpClientErrorException.NotFound.class)
                .isThrownBy(() -> restOperations.getForObject("/api/v1/non-existing-endpoint", ClientSideResponse.class));

        // now we can make a request to a non-existing endpoint without causing an exception
        var fourOhFour = nonThrowingRestOperations.getForEntity(
                "/api/v1/non-existing-endpoint", String.class
        );
        assertThat(fourOhFour.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fourOhFour.getBody()).isNull();
    }

}

```

## Conclusion

In this article, we've seen how `RestTemplate` uses `ClientHttpRequestFactory` to delegate the actual calls to a real
HTTP client or some mock server from test-kit. Also, now you know how you can use `RestTemplate` or `RestClient` test
you controllers if you like its API more than `MockMvc`.