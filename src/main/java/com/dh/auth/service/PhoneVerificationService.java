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
import com.dh.auth.service.sms.SmsProvider;
import com.dh.auth.support.PhoneNumbers;

/**
 * 휴대폰 OTP 발송/검증. 실제로 SMS가 나가는지는 {@code sms.provider} 설정에 달려 있다 —
 * {@code solapi}(기본값)이고 api-key/secret이 채워져 있을 때만
 * {@link com.dh.auth.service.sms.SolapiSmsProvider#isConfigured()}가 true가 되어 실제 발송하고,
 * {@code mock} 등 다른 값이거나 키가 비어 있으면 코드가 담긴 메시지를 로그로만 남긴다.
 * <b>{@link SmsProvider#isConfigured()}가 false인 동안은 {@link #verifyOtp}가 코드 불일치를
 * 예외 없이 통과시킨다</b>({@code SMS-MOCK-BYPASS} 로그) — 벤더 자격증명이 채워지면 이 바이패스는
 * 자동으로 비활성화되고 정확한 코드 일치를 요구한다. 현재 {@code mock}으로 두는 곳은 테스트
 * 프로파일({@code application-test.yml})뿐이지만, 운영에서 키가 비거나 잘못 설정되면 같은 바이패스가
 * 그대로 열린다는 뜻이므로 배포 전 반드시 확인할 것. 관련: auth.api#11.
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
    private final SmsProvider smsProvider;
    private final SecureRandom random = new SecureRandom();

    public PhoneVerificationService(
            PhoneOtpAttemptRepository otpAttemptRepository,
            PhoneVerificationRepository verificationRepository,
            SmsProvider smsProvider) {
        this.otpAttemptRepository = otpAttemptRepository;
        this.verificationRepository = verificationRepository;
        this.smsProvider = smsProvider;
    }

    /**
     * 사용자에게 그대로 보여줄 메시지를 예외가 들고 다니면 다국어가 불가능해지므로, 여기서는
     * 메시지 키와 인자만 들고 나가고 실제 문구는 컨트롤러가 요청 로케일로 해석한다.
     */
    public static class OtpCooldownException extends RuntimeException {
        private final long cooldownSeconds;

        public OtpCooldownException(long cooldownSeconds) {
            super("otp.cooldown");
            this.cooldownSeconds = cooldownSeconds;
        }

        public String getMessageKey() {
            return "otp.cooldown";
        }

        public Object[] getMessageArgs() {
            return new Object[] {cooldownSeconds};
        }
    }

    public static class OtpVerificationException extends RuntimeException {
        private final String messageKey;

        public OtpVerificationException(String messageKey) {
            super(messageKey);
            this.messageKey = messageKey;
        }

        public String getMessageKey() {
            return messageKey;
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
                throw new OtpCooldownException(RESEND_COOLDOWN.toSeconds());
            }
            attempt.resend(code, now.plus(OTP_TTL));
        }

        // 실제 SMS 발송
        smsProvider.sendSms(normalized, "[POSSelect] 인증번호: " + code);
    }

    @Transactional
    public void verifyOtp(String phoneNumber, String submittedCode) {
        String normalized = normalize(phoneNumber);
        PhoneOtpAttempt attempt = otpAttemptRepository.findByPhoneNumber(normalized)
                .orElseThrow(() -> new OtpVerificationException("otp.notRequested"));

        if (attempt.getOtpCode() == null) {
            throw new OtpVerificationException("otp.notRequested");
        }
        if (attempt.getOtpExpiresAt().isBefore(LocalDateTime.now())) {
            throw new OtpVerificationException("otp.expired");
        }
        if (attempt.getAttemptCount() >= MAX_ATTEMPTS) {
            throw new OtpVerificationException("otp.attemptsExceeded");
        }
        if (!attempt.getOtpCode().equals(submittedCode)) {
            if (smsProvider.isConfigured()) {
                attempt.incrementAttempt();
                throw new OtpVerificationException("otp.mismatch");
            }
            log.warn("[SMS-MOCK-BYPASS] SMS 벤더 자격증명 미설정 - 인증 코드 불일치를 무시하고 통과 처리합니다. phone={}", normalized);
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

    /**
     * 저장/조회 키는 항상 E.164 정규형이다. 하이픈만 떼던 예전 방식은 국가번호 개념이 없어서
     * 같은 번호가 표기에 따라 다른 값으로 쌓일 수 있었다 — {@link PhoneNumbers} 참고.
     */
    private String normalize(String phoneNumber) {
        return PhoneNumbers.requireE164(phoneNumber);
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}
