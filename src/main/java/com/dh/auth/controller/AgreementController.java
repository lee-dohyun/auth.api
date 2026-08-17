package com.dh.auth.controller;

import com.dh.auth.dto.AgreementDtos.AgreementResponse;
import com.dh.auth.service.AgreementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/agreements")
public class AgreementController {

    private final AgreementService agreementService;

    public AgreementController(AgreementService agreementService) {
        this.agreementService = agreementService;
    }

    @GetMapping
    public ResponseEntity<?> getAgreements(@RequestParam(name = "type", required = false) String type) {
        if (type == null || type.isBlank()) {
            return ResponseEntity.ok(agreementService.getAllAgreements());
        } else {
            return ResponseEntity.ok(agreementService.getAgreementByType(type));
        }
    }
}
