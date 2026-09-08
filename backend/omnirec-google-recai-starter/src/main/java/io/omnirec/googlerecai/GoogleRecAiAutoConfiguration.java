package io.omnirec.googlerecai;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.retail.v2.PredictionServiceClient;
import com.google.cloud.retail.v2.PredictionServiceSettings;
import com.google.cloud.retail.v2.UserEventServiceClient;
import com.google.cloud.retail.v2.UserEventServiceSettings;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Activates only when omnirec.recommendation.google-rec-ai.enabled=true.
 * Can coexist with omnirec-personalize-starter — see the note on
 * AmazonPersonalizeProvider's bean method in that starter.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "omnirec.recommendation.google-rec-ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GoogleRecAiProperties.class)
public class GoogleRecAiAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public UserEventServiceClient userEventServiceClient(GoogleRecAiProperties props) throws IOException {
        UserEventServiceSettings settings = UserEventServiceSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(loadCredentials(props)))
                .build();
        return UserEventServiceClient.create(settings);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public PredictionServiceClient predictionServiceClient(GoogleRecAiProperties props) throws IOException {
        PredictionServiceSettings settings = PredictionServiceSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(loadCredentials(props)))
                .build();
        return PredictionServiceClient.create(settings);
    }

    @Bean
    @ConditionalOnMissingBean(GoogleRecommendationsAiProvider.class)
    public GoogleRecommendationsAiProvider googleRecommendationsAiProvider(
            UserEventServiceClient userEventServiceClient, PredictionServiceClient predictionServiceClient, GoogleRecAiProperties props) {
        return new GoogleRecommendationsAiProvider(userEventServiceClient, predictionServiceClient, props);
    }

    private GoogleCredentials loadCredentials(GoogleRecAiProperties props) throws IOException {
        try (FileInputStream in = new FileInputStream(props.getCredentialsFile())) {
            return GoogleCredentials.fromStream(in);
        }
    }
}
