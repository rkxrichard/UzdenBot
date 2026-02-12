package ru.uzden.uzdenbot.yookassa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class YooKassaCreatePaymentRequest {
    private YooKassaPaymentAmount amount;
    private boolean capture = true;
    private YooKassaConfirmation confirmation;
    private String description;
    private Map<String, Object> metadata;
}
