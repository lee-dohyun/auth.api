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
import com.dh.auth.service.sms.SmsSendGuard;
import com.dh.auth.support.PhoneNumbers;

/**
 * 휴대폰 OTP 발송/검증.
 *
 * <p><b>인증 코드는 어떤 경우에도 불일치가 통과하지 않는다.</b> 예전에는
 * {@link SmsProvider#isConfigured()}가 false인 동안 코드 불일치를 그냥 통과시켰고
 * ({@code SMS-MOCK-BYPASS} 로그), 운영 클러스터에 {@code SMS_API_KEY} 가 등록된 적이 없어서
 * <b>실제로 아무 숫자나 넣어도 본인인증이 통과되고 있었다</b>(auth.api#11).
 *
 * <p>문제의 뿌리는 boolean 하나에 두 상태를 뭉갠 것이었다. 이제 둘을 나눠서 다룬다:
 *
 * <ul>
 *   <li><b>mock 모드</b>({@code sms.provider} 가 {@code solapi} 가 아님) — 개발/테스트 의도다.
 *       문자는 안 나가지만 코드가 로그에 찍히므로({@code [MOCK SMS]}) 그걸 넣어 정상 흐름을 그대로
 *       탈 수 있다. 검증은 여전히 정확한 일치를 요구한다.</li>
 *   <li><b>오설정</b>({@code solapi} 인데 자격증명이 빔) — 운영 사고다. 코드를 보낼 방법이 없으므로
 *       {@link #sendOtp} 단계에서 {@link SmsNotConfiguredException} 으로 <b>흐름을 막는다.</b>
 *       사용자에게 "지금 인증을 할 수 없다"고 말하는 것이, 받지도 않은 코드를 통과시키는 것보다 낫다.</li>
 * </ul>
 *
 * 관련: auth.api#11, #13(벤더 연동), #14(바이패스 제거).
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
    private final SmsSendGuard smsSendGuard;
    private final SecureRandom random = new SecureRandom();

    public PhoneVerificationService(
            PhoneOtpAttemptRepository otpAttemptRepository,
            PhoneVerificationRepository verificationRepository,
            SmsProvider smsProvider,
            SmsSendGuard smsSendGuard) {
        this.otpAttemptRepository = otpAttemptRepository;
        this.verificationRepository = verificationRepository;
        this.smsProvider = smsProvider;
        this.smsSendGuard = smsSendGuard;
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

    /**
     * SMS 벤더가 설정되지 않아 인증번호를 보낼 수 없는 상태. 운영 오설정이므로 503으로 나간다 —
     * 사용자 입력이 잘못된 게 아니라 우리 쪽이 준비가 안 된 것이다.
     */
    public static class SmsNotConfiguredException extends RuntimeException {

        public SmsNotConfiguredException() {
            super("otp.serviceUnavailable");
        }

        public String getMessageKey() {
            return "otp.serviceUnavailable";
        }
    }

    @Transactional
    public void sendOtp(String phoneNumber) {
        // 보낼 수 없으면 시작도 하지 않는다. 여기서 막지 않으면 사용자는 오지 않는 문자를 기다리게 되고,
        // 예전에는 그 상태에서 아무 코드나 넣으면 통과까지 됐다(auth.api#11).
        if (!smsProvider.isConfigured() && !smsProvider.isMockMode()) {
            log.error("SMS 벤더 자격증명이 설정되지 않아 OTP 발송을 거부합니다. "
                    + "SMS_API_KEY / SMS_API_SECRET / SMS_FROM_NUMBER 를 확인하세요.");
            throw new SmsNotConfiguredException();
        }

        String normalized = normalize(phoneNumber);
        LocalDateTime now = LocalDateTime.now();
        String code = generateCode();

        PhoneOtpAttempt attempt = otpAttemptRepository.findByPhoneNumber(normalized).orElse(null);
        if (attempt != null && Duration.between(attempt.getLastSentAt(), now).compareTo(RESEND_COOLDOWN) < 0) {
            throw new OtpCooldownException(RESEND_COOLDOWN.toSeconds());
        }

        // 쿨다운 다음, 상태를 건드리기 전에 상한을 본다. 순서가 뒤집히면 재발송을 연타하는 정상
        // 사용자에게 "일일 상한 초과"가 먼저 뜬다 — 60초 기다리라는 안내가 먼저 나가야 맞다.
        smsSendGuard.checkAndRecord(normalized, now);

        if (attempt == null) {
            otpAttemptRepository.save(new PhoneOtpAttempt(normalized, code, now.plus(OTP_TTL)));
        } else {
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
        // 벤더 설정 여부와 무관하게 정확한 일치를 요구한다. mock 모드에서도 코드는 로그에 찍히므로
        // 개발 흐름은 그대로 돌아간다.
        if (!attempt.getOtpCode().equals(submittedCode)) {
            attempt.incrementAttempt();
            throw new OtpVerificationException("otp.mismatch");
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
