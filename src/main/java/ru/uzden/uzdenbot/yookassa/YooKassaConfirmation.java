package ru.uzden.uzdenbot.yookassa;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class YooKassaConfirmation {
    private String type;

    @JsonProperty("confirmation_url")
    private String confirmationUrl;

    @JsonProperty("return_url")
    private String returnUrl;
}
