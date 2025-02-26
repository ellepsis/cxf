/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.systest.jaxrs.spring.boot;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Response;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

import org.apache.cxf.Bus;
import org.apache.cxf.feature.Feature;
import org.apache.cxf.jaxrs.client.JAXRSClientFactoryBean;
import org.apache.cxf.metrics.MetricsFeature;
import org.apache.cxf.metrics.MetricsProvider;
import org.apache.cxf.systest.jaxrs.applications.LibraryApplication;
import org.apache.cxf.systest.jaxrs.resources.Book;
import org.apache.cxf.systest.jaxrs.resources.LibraryApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.SocketUtils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import io.micrometer.core.instrument.search.RequiredSearch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.empty;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = SpringJaxrsApplicationTest.TestConfig.class)
@ActiveProfiles("jaxrs")
public class SpringJaxrsApplicationTest {

    @Autowired
    private MeterRegistry registry;
    
    @Autowired
    private MetricsProvider metricsProvider;
    
    @LocalServerPort
    private int port;

    @EnableAutoConfiguration
    static class TestConfig {
        @Bean
        public Application application() {
            return new LibraryApplication();
        }
        
        @Bean
        public Feature metricsFeature(MetricsProvider metricsProvider) {
            return new MetricsFeature(metricsProvider);
        }
    }

    @Autowired
    public void setBus(Bus bus) {
        // By default, the exception are propagated and out fault interceptors are not called 
        bus.setProperty("org.apache.cxf.propagate.exception", Boolean.FALSE);
    }

    @AfterEach
    public void clear() {
        registry.clear();
    }

    @Test
    public void testJaxrsSuccessMetric() {
        final WebTarget target = createWebTarget();
        
        try (Response r = target.request().get()) {
            assertThat(r.getStatus()).isEqualTo(200);
        }

        await()
            .atMost(Duration.ofSeconds(1))
            .ignoreException(MeterNotFoundException.class)
            .until(() -> registry.get("cxf.server.requests").timers(), not(empty()));
        RequiredSearch serverRequestMetrics = registry.get("cxf.server.requests");

        Map<Object, Object> serverTags = serverRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(serverTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "getBooks"),
                entry("uri", "/api/app/library"),
                entry("outcome", "SUCCESS"),
                entry("status", "200"));
        
        RequiredSearch clientRequestMetrics = registry.get("cxf.client.requests");

        Map<Object, Object> clientTags = clientRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(clientTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "UNKNOWN"),
                entry("uri", "http://localhost:" + port + "/api/app/library"),
                entry("outcome", "SUCCESS"),
                entry("status", "200"));
    }
    
    @Test
    public void testJaxrsSubresourceSuccessMetric() {
        final WebTarget target = createWebTarget().path("catalog").path("cxf");
        
        try (Response r = target.request().get()) {
            assertThat(r.getStatus()).isEqualTo(200);
        }
        
        await()
            .atMost(Duration.ofSeconds(1))
            .ignoreException(MeterNotFoundException.class)
            .until(() -> registry.get("cxf.server.requests").timers(), not(empty()));
        RequiredSearch serverRequestMetrics = registry.get("cxf.server.requests");

        Map<Object, Object> serverTags = serverRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(serverTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "catalog"),
                entry("uri", "/api/app/library/catalog/{catalog}"),
                entry("outcome", "SUCCESS"),
                entry("status", "200"));
        
        RequiredSearch clientRequestMetrics = registry.get("cxf.client.requests");

        Map<Object, Object> clientTags = clientRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(clientTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "UNKNOWN"),
                entry("uri", "http://localhost:" + port + "/api/app/library/catalog/cxf"),
                entry("outcome", "SUCCESS"),
                entry("status", "200"));
    }
    
    @Test
    public void testJaxrsFailedMetric() {
        final WebTarget target = createWebTarget();
        
        assertThatThrownBy(() -> target.path("100").request().get(Book.class))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Not Found");

        await()
            .atMost(Duration.ofSeconds(1))
            .ignoreException(MeterNotFoundException.class)
            .until(() -> registry.get("cxf.server.requests").timers(), not(empty()));
        RequiredSearch serverRequestMetrics = registry.get("cxf.server.requests");

        Map<Object, Object> serverTags = serverRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(serverTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "getBook"),
                entry("uri", "/api/app/library/{id}"),
                entry("outcome", "CLIENT_ERROR"),
                entry("status", "404"));
        
        RequiredSearch clientRequestMetrics = registry.get("cxf.client.requests");

        Map<Object, Object> clientTags = clientRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(clientTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "UNKNOWN"),
                entry("uri", "http://localhost:" + port + "/api/app/library/100"),
                entry("outcome", "CLIENT_ERROR"),
                entry("status", "404"));
    }
    
    @Test
    public void testJaxrsExceptionMetric() {
        final WebTarget target = createWebTarget();
        
        assertThatThrownBy(() -> target.request().delete(String.class))
            .isInstanceOf(InternalServerErrorException.class)
            .hasMessageContaining("Internal Server Error");

        await()
            .atMost(Duration.ofSeconds(1))
            .ignoreException(MeterNotFoundException.class)
            .until(() -> registry.get("cxf.server.requests").timers(), not(empty()));
        RequiredSearch serverRequestMetrics = registry.get("cxf.server.requests");

        Map<Object, Object> serverTags = serverRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(serverTags)
            .containsOnly(
                entry("exception", "UnsupportedOperationException"),
                entry("method", "DELETE"),
                entry("operation", "deleteBooks"),
                entry("uri", "/api/app/library"),
                entry("outcome", "SERVER_ERROR"),
                entry("status", "500"));
        
        RequiredSearch clientRequestMetrics = registry.get("cxf.client.requests");

        Map<Object, Object> clientTags = clientRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(clientTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "DELETE"),
                entry("operation", "UNKNOWN"),
                entry("uri", "http://localhost:" + port + "/api/app/library"),
                entry("outcome", "SERVER_ERROR"),
                entry("status", "500"));
    }
    
    @Test
    public void testJaxrsClientExceptionMetric() {
        final int fakePort = SocketUtils.findAvailableTcpPort();
        
        final WebTarget target = ClientBuilder
            .newClient()
            .register(new MetricsFeature(metricsProvider))
            .target("http://localhost:" + fakePort + "/api/library");
        
        assertThatThrownBy(() -> target.request().delete(String.class))
            .isInstanceOf(ProcessingException.class)
            .hasMessageContaining("Connection refused");

        // no server meters
        assertThat(registry.getMeters())
            .noneMatch(m -> "cxf.server.requests".equals(m.getId().getName()));
        
        RequiredSearch clientRequestMetrics = registry.get("cxf.client.requests");

        Map<Object, Object> clientTags = clientRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(clientTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "DELETE"),
                entry("operation", "UNKNOWN"),
                entry("uri", "http://localhost:" + fakePort + "/api/library"),
                entry("outcome", "UNKNOWN"),
                entry("status", "UNKNOWN"));
    }
    
    @Test
    public void testJaxrsProxySuccessMetric() {
        final LibraryApi api = createApi(port);
        
        try (Response r = api.getBooks(1)) {
            assertThat(r.getStatus()).isEqualTo(200);
        }
        
        await()
            .atMost(Duration.ofSeconds(1))
            .ignoreException(MeterNotFoundException.class)
            .until(() -> registry.get("cxf.server.requests").timers(), not(empty()));
        RequiredSearch serverRequestMetrics = registry.get("cxf.server.requests");

        Map<Object, Object> serverTags = serverRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(serverTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "getBooks"),
                entry("uri", "/api/app/library"),
                entry("outcome", "SUCCESS"),
                entry("status", "200"));
        
        RequiredSearch clientRequestMetrics = registry.get("cxf.client.requests");

        Map<Object, Object> clientTags = clientRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(clientTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "UNKNOWN"),
                entry("uri", "http://localhost:" + port + "/api/app/library"),
                entry("outcome", "SUCCESS"),
                entry("status", "200"));
    }
    
    @Test
    public void testJaxrsProxyExceptionMetric() {
        final LibraryApi api = createApi(port);
        
        assertThatThrownBy(() -> api.deleteBooks())
            .isInstanceOf(InternalServerErrorException.class)
            .hasMessageContaining("Internal Server Error");

        await()
            .atMost(Duration.ofSeconds(1))
            .ignoreException(MeterNotFoundException.class)
            .until(() -> registry.get("cxf.server.requests").timers(), not(empty()));
        RequiredSearch serverRequestMetrics = registry.get("cxf.server.requests");

        Map<Object, Object> serverTags = serverRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(serverTags)
            .containsOnly(
                entry("exception", "UnsupportedOperationException"),
                entry("method", "DELETE"),
                entry("operation", "deleteBooks"),
                entry("uri", "/api/app/library"),
                entry("outcome", "SERVER_ERROR"),
                entry("status", "500"));
        
        RequiredSearch clientRequestMetrics = registry.get("cxf.client.requests");

        Map<Object, Object> clientTags = clientRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(clientTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "DELETE"),
                entry("operation", "UNKNOWN"),
                entry("uri", "http://localhost:" + port + "/api/app/library"),
                entry("outcome", "SERVER_ERROR"),
                entry("status", "500"));
    }
    
    @Test
    public void testJaxrsProxyFailedMetric() {
        final LibraryApi api = createApi(port);

        try (Response r = api.getBook("100")) {
            assertThat(r.getStatus()).isEqualTo(404);
        }

        await()
            .atMost(Duration.ofSeconds(1))
            .ignoreException(MeterNotFoundException.class)
            .until(() -> registry.get("cxf.server.requests").timers(), not(empty()));
        RequiredSearch serverRequestMetrics = registry.get("cxf.server.requests");

        Map<Object, Object> serverTags = serverRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(serverTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "getBook"),
                entry("uri", "/api/app/library/{id}"),
                entry("outcome", "CLIENT_ERROR"),
                entry("status", "404"));
        
        RequiredSearch clientRequestMetrics = registry.get("cxf.client.requests");

        Map<Object, Object> clientTags = clientRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(clientTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "GET"),
                entry("operation", "UNKNOWN"),
                entry("uri", "http://localhost:" + port + "/api/app/library/100"),
                entry("outcome", "CLIENT_ERROR"),
                entry("status", "404"));
    }
    
    @Test
    public void testJaxrsProxyClientExceptionMetric() {
        final int fakePort = SocketUtils.findAvailableTcpPort();
        final LibraryApi api = createApi(fakePort);

        assertThatThrownBy(() -> api.deleteBooks())
            .isInstanceOf(ProcessingException.class)
            .hasMessageContaining("Connection refused");

        // no server meters
        assertThat(registry.getMeters())
            .noneMatch(m -> "cxf.server.requests".equals(m.getId().getName()));
        
        RequiredSearch clientRequestMetrics = registry.get("cxf.client.requests");

        Map<Object, Object> clientTags = clientRequestMetrics.timer().getId().getTags().stream()
                .collect(toMap(Tag::getKey, Tag::getValue));

        assertThat(clientTags)
            .containsOnly(
                entry("exception", "None"),
                entry("method", "DELETE"),
                entry("operation", "UNKNOWN"),
                entry("uri", "http://localhost:" + fakePort + "/api/app/library"),
                entry("outcome", "UNKNOWN"),
                entry("status", "UNKNOWN"));
    }
    
    private LibraryApi createApi(int portToUse) {
        final JAXRSClientFactoryBean factory = new JAXRSClientFactoryBean();
        factory.setAddress("http://localhost:" + portToUse + "/api/app/library");
        factory.setFeatures(Arrays.asList(new MetricsFeature(metricsProvider)));
        factory.setResourceClass(LibraryApi.class);
        return factory.create(LibraryApi.class);
    }

    private WebTarget createWebTarget() {
        return ClientBuilder
            .newClient()
            .register(JacksonJsonProvider.class)
            .register(new MetricsFeature(metricsProvider))
            .target("http://localhost:" + port + "/api/app/library");
    }

}
