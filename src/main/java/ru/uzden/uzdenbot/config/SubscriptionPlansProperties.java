package ru.uzden.uzdenbot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.subscription-plans")
public class SubscriptionPlansProperties {

    private Plan plan1 = new Plan(1, 30, 149, "1 месяц");
    private Plan plan2 = new Plan(2, 60, 249, "2 месяца");

    @Data
    public static class Plan {
        private int months;
        private int days;
        private int price;
        private String label;

        public Plan() {
        }

        public Plan(int months, int days, int price, String label) {
            this.months = months;
            this.days = days;
            this.price = price;
            this.label = label;
        }
    }
}
