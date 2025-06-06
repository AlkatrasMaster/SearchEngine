package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Для маскировки запросов как обычных посещений пользователей
@Configuration
@Getter
@Setter
@ConfigurationProperties(prefix = "crawler-settings")
public class CrawlerSettings {

    private String userAgent;
    private String referrer;
}
