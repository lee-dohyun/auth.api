package com.dh.auth.service;

import com.dh.auth.dto.AgreementDtos.AgreementArticleResponse;
import com.dh.auth.dto.AgreementDtos.AgreementResponse;
import com.dh.auth.dto.AgreementDtos.AllAgreementsResponse;
import com.dh.auth.entity.Agreement;
import com.dh.auth.repository.AgreementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AgreementService {

    private final AgreementRepository agreementRepository;

    public AgreementService(AgreementRepository agreementRepository) {
        this.agreementRepository = agreementRepository;
    }

    public AgreementResponse getAgreementByType(String type) {
        Agreement agreement = agreementRepository.findByType(type)
                .orElseThrow(() -> new IllegalArgumentException("Invalid agreement type: " + type));
        return toResponse(agreement);
    }

    public AllAgreementsResponse getAllAgreements() {
        AgreementResponse terms = getAgreementByType("terms");
        AgreementResponse privacy = getAgreementByType("privacy");
        return new AllAgreementsResponse(terms, privacy);
    }

    private AgreementResponse toResponse(Agreement agreement) {
        var articles = agreement.getArticles().stream()
                .map(a -> new AgreementArticleResponse(a.getTitle(), a.getBody()))
                .toList();
        return new AgreementResponse(agreement.getTitle(), articles);
    }
}
