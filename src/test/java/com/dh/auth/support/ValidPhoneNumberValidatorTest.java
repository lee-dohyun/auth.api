package com.dh.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import com.dh.auth.dto.AuthDtos.SignupRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** DTO에 실제로 제약이 붙어 있는지(= 해외 번호가 400으로 안 막히는지) 확인한다. */
class ValidPhoneNumberValidatorTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    private final Validator validator = FACTORY.getValidator();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void 해외_번호로도_가입_요청이_통과한다() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);

        assertThat(violations(signupWith("+819012345678"))).isEmpty();
    }

    @Test
    void 국내_번호는_기존_표기_그대로_통과한다() {
        assertThat(violations(signupWith("010-1234-5678"))).isEmpty();
    }

    @Test
    void 유효하지_않은_번호는_phoneNumber에서_걸린다() {
        assertThat(violations(signupWith("2125550123")))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("phoneNumber");
    }

    /** 비어 있는 경우는 @NotBlank가 담당한다 — 같은 필드에서 메시지가 두 번 뜨지 않도록. */
    @Test
    void 빈값은_NotBlank만_걸린다() {
        assertThat(violations(signupWith("")))
                .extracting(v -> v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
                .containsExactly("NotBlank");
    }

    private SignupRequest signupWith(String phoneNumber) {
        return new SignupRequest("user@example.com", "password1234", "홍길동", phoneNumber, false);
    }

    private Set<jakarta.validation.ConstraintViolation<SignupRequest>> violations(SignupRequest request) {
        return validator.validate(request);
    }
}
