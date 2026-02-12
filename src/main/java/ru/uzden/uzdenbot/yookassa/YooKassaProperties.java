package ru.uzden.uzdenbot.yookassa;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "yookassa")
public class YooKassaProperties {
    private String shopId;
    private String secretKey;
    private String apiBase = "https://api.yookassa.ru/v3";
    private String returnUrl;
    private String webhookSecret;
}
