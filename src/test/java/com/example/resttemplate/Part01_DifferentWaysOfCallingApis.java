package com.example.resttemplate;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import okhttp3.OkHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.assertj.core.api.Assertions;
import org.glassfish.jersey.client.proxy.WebResourceFactory;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import retrofit2.converter.gson.GsonConverterFactory;

import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class Part01_DifferentWaysOfCallingApis extends TestWithMockServer {

    public Part01_DifferentWaysOfCallingApis(MockServerClient mockServer) {
        super(mockServer);
    }

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

        // now making the call to the bad-req endpoint
        HttpRequest badRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:1090/bad-req-endpoint"))
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> badResponse = client.send(badRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(badResponse.statusCode()).isEqualTo(400);
        assertThat(badResponse.body()).isEqualTo("{\"error\":\"Bad request\"}");
    }

    @Test
    void withApacheHttpClient() throws Exception {
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            org.apache.hc.client5.http.classic.methods.HttpGet request = new org.apache.hc.client5.http.classic.methods.HttpGet("http://localhost:1090/some-endpoint");
            request.addHeader("Accept", "application/json");
            CloseableHttpResponse response = client.execute(request);
            assertThat(response.getCode()).isEqualTo(200);

            var asObject = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response.getEntity().getContent(), SampleResponseModel.class);

            assertThat(asObject).isEqualTo(new SampleResponseModel("John", 25));

            // now making the call to the bad-req endpoint
            org.apache.hc.client5.http.classic.methods.HttpGet badRequest = new org.apache.hc.client5.http.classic.methods.HttpGet("http://localhost:1090/bad-req-endpoint");
            CloseableHttpResponse badResponse = client.execute(badRequest);
            assertThat(badResponse.getCode()).isEqualTo(400);
            assertThat(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(badResponse.getEntity().getContent()).get("error").asText()).isEqualTo("Bad response for bad request");
        }
    }

    @Test
    void withOkHttpClient() throws Exception {
        OkHttpClient client = new okhttp3.OkHttpClient();

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url("http://localhost:1090/some-endpoint")
                .addHeader("Accept", "application/json")
                .build();

        okhttp3.Response response = client.newCall(request).execute();
        assertThat(response.code()).isEqualTo(200);

        var asObject = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(response.body().string(), SampleResponseModel.class);
        assertThat(asObject).isEqualTo(new SampleResponseModel("John", 25));

        // now making the call to the bad-req endpoint
        okhttp3.Request badRequest = new okhttp3.Request.Builder()
                .url("http://localhost:1090/bad-req-endpoint")
                .build();
        okhttp3.Response badResponse = client.newCall(badRequest).execute();
        assertThat(badResponse.code()).isEqualTo(400);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(badResponse.body().string()).get("error").asText()).isEqualTo("Bad response for bad request");
    }

    @Test
    void withRetrofit() throws Exception {
        interface RemoteRetrofitService {
            @retrofit2.http.GET("/some-endpoint")
            retrofit2.Call<SampleResponseModel> getOurDomainModel();

            @retrofit2.http.GET("/bad-req-endpoint")
            retrofit2.Call<SampleResponseModel> getBadResponse();
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

        // non-ok responses still need to be handled
        retrofit2.Call<SampleResponseModel> badCall = service.getBadResponse();
        retrofit2.Response<SampleResponseModel> badResponse = badCall.execute();
        assertThat(badResponse.code()).isEqualTo(400);
    }

    @Test
    void withJerseyClient() {
        try (var client = jakarta.ws.rs.client.ClientBuilder.newClient()) {
            SampleResponseModel response = client.target("http://localhost:1090/some-endpoint")
                    .request()
                    .get(SampleResponseModel.class);

            assertThat(response).isEqualTo(new SampleResponseModel("John", 25));

            // making the call to the bad-req endpoint will throw  jakarta.ws.rs.BadRequestException
            Assertions.assertThatExceptionOfType(
                            jakarta.ws.rs.BadRequestException.class
                    ).isThrownBy(
                            () -> client.target("http://localhost:1090/bad-req-endpoint")
                                    .request()
                                    .get(ErrorResponseModel.class)
                    ).withMessage("HTTP 400 Bad Request")
                    .extracting(e -> e.getResponse().readEntity(ErrorResponseModel.class))
                    .isEqualTo(new ErrorResponseModel("Bad response for bad request"));
        }
    }

    @Test
    void withJerseyClient_viaApiInterfaceProxy() {

        @Produces("application/json")
        @Consumes("application/json")
        interface RemoteApi {
            @GET
            @Path("/some-endpoint")
            SampleResponseModel getOurDomainModel();

            @GET
            @Path("/bad-req-endpoint")
            SampleResponseModel getBadResponse();
        }

        var target = jakarta.ws.rs.client.ClientBuilder.newClient().target("http://localhost:1090");

        var ourServiceProxy = WebResourceFactory.newResource(RemoteApi.class, target);

        SampleResponseModel response = ourServiceProxy.getOurDomainModel();

        assertThat(response).isEqualTo(new SampleResponseModel("John", 25));

        // making the call to the bad-req endpoint will throw  jakarta.ws.rs.BadRequestException
        Assertions.assertThatExceptionOfType(
                        jakarta.ws.rs.BadRequestException.class
                ).isThrownBy(
                        () -> ourServiceProxy.getBadResponse()
                ).withMessage("HTTP 400 Bad Request")
                .extracting(e -> e.getResponse().readEntity(ErrorResponseModel.class))
                .isEqualTo(new ErrorResponseModel("Bad response for bad request"));
    }

}
