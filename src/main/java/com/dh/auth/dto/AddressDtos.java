package com.dh.auth.dto;

import jakarta.validation.constraints.NotBlank;

import com.dh.auth.entity.MemberAddress;

public class AddressDtos {

    public record CreateAddressRequest(
            String label,
            @NotBlank(message = "받는 사람을 입력하세요.") String recipientName,
            @NotBlank(message = "연락처를 입력하세요.") String phoneNumber,
            @NotBlank(message = "우편번호를 입력하세요.") String zipCode,
            @NotBlank(message = "주소를 입력하세요.") String address1,
            String address2,
            Boolean isDefault) {
    }

    public record UpdateAddressRequest(
            String label,
            @NotBlank(message = "받는 사람을 입력하세요.") String recipientName,
            @NotBlank(message = "연락처를 입력하세요.") String phoneNumber,
            @NotBlank(message = "우편번호를 입력하세요.") String zipCode,
            @NotBlank(message = "주소를 입력하세요.") String address1,
            String address2) {
    }

    public record AddressResponse(
            Long id,
            String label,
            String recipientName,
            String phoneNumber,
            String zipCode,
            String address1,
            String address2,
            boolean isDefault) {

        public static AddressResponse from(MemberAddress address) {
            return new AddressResponse(
                    address.getId(),
                    address.getLabel(),
                    address.getRecipientName(),
                    address.getPhoneNumber(),
                    address.getZipCode(),
                    address.getAddress1(),
                    address.getAddress2(),
                    address.isDefault());
        }
    }
}
