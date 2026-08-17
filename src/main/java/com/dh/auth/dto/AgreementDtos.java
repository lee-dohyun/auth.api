package com.dh.auth.dto;

import java.util.List;

public class AgreementDtos {

    public record AgreementArticleResponse(
            String title,
            String body
    ) {}

    public record AgreementResponse(
            String title,
            List<AgreementArticleResponse> articles
    ) {}

    public record AllAgreementsResponse(
            AgreementResponse terms,
            AgreementResponse privacy
    ) {}
}
