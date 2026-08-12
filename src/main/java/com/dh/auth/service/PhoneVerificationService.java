package com.dh.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dh.auth.entity.PhoneOtpAttempt;
import com.dh.auth.entity.PhoneVerification;
import com.dh.auth.repository.PhoneOtpAttemptRepository;
import com.dh.auth.repository.PhoneVerificationRepository;

/**
 * 휴대폰 OTP 발송/검증. 실제 SMS 발급자가 아직 없어 mock으로 동작한다 — payment(mock, 항상 성공)와
 * 동일한 패턴으로, 코드는 로그에만 출력하고 검증 로직 자체는 실제로 동작한다.
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);

    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration SIGNUP_VERIFICATION_WINDOW = Duration.ofMinutes(30);
    private static final int MAX_ATTEMPTS = 5;

    private final PhoneOtpAttemptRepository otpAttemptRepository;
    private final PhoneVerificationRepository verificationRepository;
    private final SecureRandom random = new SecureRandom();

    public PhoneVerificationService(
            PhoneOtpAttemptRepository otpAttemptRepository,
            PhoneVerificationRepository verificationRepository) {
        this.otpAttemptRepository = otpAttemptRepository;
        this.verificationRepository = verificationRepository;
    }

    public static class OtpCooldownException extends RuntimeException {
        public OtpCooldownException() {
            super("재발송은 60초 후에 가능합니다.");
        }
    }

    public static class OtpVerificationException extends RuntimeException {
        public OtpVerificationException(String message) {
            super(message);
        }
    }

    @Transactional
    public void sendOtp(String phoneNumber) {
        String normalized = normalize(phoneNumber);
        LocalDateTime now = LocalDateTime.now();
        String code = generateCode();

        PhoneOtpAttempt attempt = otpAttemptRepository.findByPhoneNumber(normalized).orElse(null);
        if (attempt == null) {
            otpAttemptRepository.save(new PhoneOtpAttempt(normalized, code, now.plus(OTP_TTL)));
        } else {
            if (Duration.between(attempt.getLastSentAt(), now).compareTo(RESEND_COOLDOWN) < 0) {
                throw new OtpCooldownException();
            }
            attempt.resend(code, now.plus(OTP_TTL));
        }

        // mock 발송 — 실제 SMS 연동 전까지는 로그로만 코드 확인
        log.info("[MOCK SMS] {}로 인증번호 발송: {}", normalized, code);
    }

    @Transactional
    public void verifyOtp(String phoneNumber, String submittedCode) {
        String normalized = normalize(phoneNumber);
        PhoneOtpAttempt attempt = otpAttemptRepository.findByPhoneNumber(normalized)
                .orElseThrow(() -> new OtpVerificationException("인증번호를 먼저 요청하세요."));

        if (attempt.getOtpCode() == null) {
            throw new OtpVerificationException("인증번호를 먼저 요청하세요.");
        }
        if (attempt.getOtpExpiresAt().isBefore(LocalDateTime.now())) {
            throw new OtpVerificationException("인증번호가 만료되었습니다. 다시 요청하세요.");
        }
        if (attempt.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new OtpVerificationException("인증 시도 횟수를 초과했습니다. 다시 요청하세요.");
        }
        if (!attempt.getOtpCode().equals(submittedCode)) {
            attempt.incrementAttempt();
            throw new OtpVerificationException("인증번호가 일치하지 않습니다.");
        }

        attempt.clearOtpCode();
        verificationRepository.save(new PhoneVerification(null, normalized));
    }

    /** signup 시점에 "방금(30분 이내) 인증된 번호인지" 확인. */
    @Transactional(readOnly = true)
    public boolean isRecentlyVerified(String phoneNumber) {
        String normalized = normalize(phoneNumber);
        return verificationRepository.findTopByPhoneNumberAndMemberIsNullOrderByVerifiedAtDesc(normalized)
                .filter(v -> Duration.between(v.getVerifiedAt(), LocalDateTime.now()).compareTo(SIGNUP_VERIFICATION_WINDOW) <= 0)
                .isPresent();
    }

    @Transactional
    public void linkVerificationToMember(String phoneNumber, com.dh.auth.entity.Member member) {
        String normalized = normalize(phoneNumber);
        verificationRepository.findTopByPhoneNumberAndMemberIsNullOrderByVerifiedAtDesc(normalized)
                .ifPresent(v -> v.linkMember(member));
    }

    private String normalize(String phoneNumber) {
        return phoneNumber.replace("-", "");
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
