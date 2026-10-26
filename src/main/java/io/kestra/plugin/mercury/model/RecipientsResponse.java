package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecipientsResponse {
    private Integer total;
    private List<Recipient> recipients;
    private CursorPage page;
}
