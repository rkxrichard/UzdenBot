package ru.uzden.uzdenbot.yookassa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class YooKassaPayment {
    private String id;
    private String status;
    private YooKassaPaymentAmount amount;
    private YooKassaConfirmation confirmation;
    private boolean paid;
    private Map<String, Object> metadata;
}
