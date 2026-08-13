package com.dh.auth.support;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidPhoneNumberValidator implements ConstraintValidator<ValidPhoneNumber, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true; // 필수 여부는 @NotBlank의 몫
        }
        return PhoneNumbers.toE164(value).isPresent();
    }
}
