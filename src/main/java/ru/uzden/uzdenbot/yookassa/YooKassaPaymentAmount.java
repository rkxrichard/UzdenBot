package ru.uzden.uzdenbot.yookassa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class YooKassaPaymentAmount {
    private String value;
    private String currency;
}
