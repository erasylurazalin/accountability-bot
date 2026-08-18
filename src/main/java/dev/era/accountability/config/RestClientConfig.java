package dev.era.accountability.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * Plain JDK HttpURLConnection factory — no extra HTTP client dependency.
     * Load here is one long-poll connection plus occasional sends.
     *
     * The read timeout must exceed the long-poll timeout, otherwise every
     * getUpdates call that legitimately waits out the full window is aborted
     * client-side and looks like an error.
     */
    @Bean
    RestClient telegramRestClient(BotProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(props.pollTimeout() + 20L));

        return RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.apiBaseUrl() + "/bot" + props.token())
                .build();
    }
}
