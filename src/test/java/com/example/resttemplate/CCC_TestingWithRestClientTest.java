package com.example.resttemplate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Similar to @DataJpaTest, @WebMvcTest, @DataMongoTest, ...
 * this annotation is used to test the REST client and to only load
 * the beans that are needed to test the REST client.
 */
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

    @Test
    void beansFromDbContext_wereNotLoaded() {
        assertThat(applicationContext.getBeanNamesForType(DataSource.class)).isEmpty();
    }

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

        mockServer.verify();
    }

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

        mockServer.verify();
    }


}
