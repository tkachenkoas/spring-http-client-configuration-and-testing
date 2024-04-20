package com.example.resttemplate;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.httpcomponents.hc5.PoolingHttpClientConnectionManagerMetricsBinder;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.PropertyResolver;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import static org.apache.hc.core5.pool.PoolConcurrencyPolicy.LAX;

public class Part06_02_FailingFast {

    public static RestTemplateBuilder configurableFailProofRestTemplateBuilder(
            String clientName,
            MeterRegistry meterRegistry,
            PropertyResolver propertyResolver,
            Optional<CircuitBreaker> circuitBreaker
    ) {
        String propertyPrefix = "http.client." + clientName;
        BiFunction<String, Integer, Integer> getIntProperty = (key, defaultValue) -> propertyResolver.getProperty(
                propertyPrefix + "." + key, Integer.class, defaultValue
        );
        List<ClientHttpRequestInterceptor> interceptors = circuitBreaker
                .map(cb -> (ClientHttpRequestInterceptor) (request, body, execution) -> {
                    try {
                        return cb.executeCheckedSupplier(() -> execution.execute(request, body));
                    } catch (Throwable e) {
                        throw e instanceof IOException
                                ? (IOException) e
                                : new IOException(e);
                    }
                }).map(List::of)
                .orElse(List.of());

        Timeout socketTimeout = Timeout.ofSeconds(
                getIntProperty.apply("response-timeout-seconds", 60)
        );

        return new RestTemplateBuilder()
                .additionalInterceptors(interceptors)
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
                                            .setSocketTimeout(socketTimeout)
                                            .build()
                            )
                            .build();
                    new PoolingHttpClientConnectionManagerMetricsBinder(connectionManager, clientName)
                            .bindTo(meterRegistry);
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
                                                    .setResponseTimeout(socketTimeout)
                                                    .build()
                                    )
                                    .build()
                    );
                });
    }

}
