package com.dh.auth.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.dh.auth.dto.AddressDtos.AddressResponse;
import com.dh.auth.dto.AddressDtos.CreateAddressRequest;
import com.dh.auth.dto.AddressDtos.UpdateAddressRequest;
import com.dh.auth.entity.MemberAddress;
import com.dh.auth.service.MemberAddressService;

import jakarta.validation.Valid;

@Validated
@RestController
public class MemberAddressController {

    private final MemberAddressService memberAddressService;

    public MemberAddressController(MemberAddressService memberAddressService) {
        this.memberAddressService = memberAddressService;
    }

    @GetMapping("/api/auth/addresses")
    public ResponseEntity<List<AddressResponse>> list(
            @RequestHeader(value = "X-User-Email", required = false) String email) {
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<AddressResponse> addresses = memberAddressService.list(email).stream()
                .map(AddressResponse::from)
                .toList();
        return ResponseEntity.ok(addresses);
    }

    @PostMapping("/api/auth/addresses")
    public ResponseEntity<AddressResponse> create(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @Valid @RequestBody CreateAddressRequest request) {
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        MemberAddress created = memberAddressService.create(email, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AddressResponse.from(created));
    }

    @PutMapping("/api/auth/addresses/{addressId}")
    public ResponseEntity<AddressResponse> update(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @PathVariable Long addressId,
            @Valid @RequestBody UpdateAddressRequest request) {
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        MemberAddress updated = memberAddressService.update(email, addressId, request);
        return ResponseEntity.ok(AddressResponse.from(updated));
    }

    @DeleteMapping("/api/auth/addresses/{addressId}")
    public ResponseEntity<Void> delete(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @PathVariable Long addressId) {
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        memberAddressService.delete(email, addressId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/auth/addresses/{addressId}/default")
    public ResponseEntity<AddressResponse> setDefault(
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @PathVariable Long addressId) {
        if (email == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        MemberAddress updated = memberAddressService.setDefault(email, addressId);
        return ResponseEntity.ok(AddressResponse.from(updated));
    }
}
