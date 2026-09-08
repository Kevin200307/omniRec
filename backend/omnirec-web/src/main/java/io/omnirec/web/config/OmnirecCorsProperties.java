package io.omnirec.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "omnirec.web.cors")
public class OmnirecCorsProperties {

    /** Storefront origins allowed to call this backend directly from the browser (e.g. http://localhost:3000). */
    private List<String> allowedOrigins = List.of();

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
