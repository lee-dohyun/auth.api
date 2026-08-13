package com.dh.auth.dto;

import jakarta.validation.constraints.NotBlank;

import com.dh.auth.entity.MemberAddress;

public class AddressDtos {

    public record CreateAddressRequest(
            String label,
            @NotBlank(message = "{validation.recipientName.required}") String recipientName,
            @NotBlank(message = "{validation.contact.required}") String phoneNumber,
            @NotBlank(message = "{validation.zipCode.required}") String zipCode,
            @NotBlank(message = "{validation.address.required}") String address1,
            String address2,
            Boolean isDefault) {
    }

    public record UpdateAddressRequest(
            String label,
            @NotBlank(message = "{validation.recipientName.required}") String recipientName,
            @NotBlank(message = "{validation.contact.required}") String phoneNumber,
            @NotBlank(message = "{validation.zipCode.required}") String zipCode,
            @NotBlank(message = "{validation.address.required}") String address1,
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
