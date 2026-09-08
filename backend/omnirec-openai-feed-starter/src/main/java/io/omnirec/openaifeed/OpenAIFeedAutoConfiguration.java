package io.omnirec.openaifeed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.omnirec.catalog.providers.openai.OpenAIFeedDiagnostic;
import io.omnirec.catalog.providers.openai.OpenAIProductFeedProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.net.http.HttpClient;
import java.time.Duration;

/** Activates only when omnirec.providers.openai-feed.enabled=true. */
@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.providers.openai-feed", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OpenAIFeedProperties.class)
public class OpenAIFeedAutoConfiguration {

    /** Self-contained fallback — an ObjectMapper bean is typically already present via Spring Boot's own Jackson auto-configuration whenever omnirec-web (spring-boot-starter-web) is on the classpath, but this starter doesn't assume that. */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper openAIFeedObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean(name = "openAIFeedHttpClient")
    public HttpClient openAIFeedHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Bean
    @ConditionalOnMissingBean(OpenAIProductFeedProvider.class)
    public OpenAIProductFeedProvider openAIProductFeedProvider(HttpClient openAIFeedHttpClient, ObjectMapper objectMapper, OpenAIFeedProperties props) {
        return new OpenAIProductFeedProvider(openAIFeedHttpClient, objectMapper, props);
    }

    @Bean
    @ConditionalOnMissingBean(OpenAIFeedDiagnostic.class)
    public OpenAIFeedDiagnostic openAIFeedDiagnostic(HttpClient openAIFeedHttpClient, OpenAIFeedProperties props) {
        return new OpenAIFeedDiagnostic(openAIFeedHttpClient, props);
    }
}
