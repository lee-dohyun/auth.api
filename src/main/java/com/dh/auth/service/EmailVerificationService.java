package com.dh.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * order.api의 OrderNotificationService와 동일한 패턴(Spring Mail, 자체 메일서버 SMTP 릴레이)으로
 * 회원가입 이메일 인증 메일을 발송한다.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final JavaMailSender mailSender;
    private final String frontendBaseUrl;
    private final String mailFrom;
    private final String brandName;

    public EmailVerificationService(
            JavaMailSender mailSender,
            @Value("${app.frontend-base-url}") String frontendBaseUrl,
            @Value("${app.mail-from}") String mailFrom,
            @Value("${app.brand-name}") String brandName) {
        this.mailSender = mailSender;
        this.frontendBaseUrl = frontendBaseUrl;
        this.mailFrom = mailFrom;
        this.brandName = brandName;
    }

    /**
     * 인증 메일 발송을 시도한다. 실패해도 예외를 던지지 않고 로그만 남긴다 —
     * 회원가입 자체(계정 생성)는 메일 발송 성공 여부와 무관하게 이미 끝난 상태이므로,
     * 여기서 던지면 정상 가입한 사용자에게 500을 돌려주는 꼴이 된다(재발송 API로 복구 가능).
     */
    public void sendVerificationEmail(String email, String name, String token) {
        String verifyUrl = frontendBaseUrl + "/verify?email=" + urlEncode(email) + "&token=" + urlEncode(token);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("[" + brandName + "] 이메일 인증을 완료해주세요");
            message.setText(
                    (name == null || name.isBlank() ? email : name) + "님, 회원가입해주셔서 감사합니다.\n\n"
                            + "아래 링크를 클릭하면 이메일 인증이 완료됩니다 (24시간 이내 유효):\n"
                            + verifyUrl + "\n\n"
                            + "본인이 요청하지 않았다면 이 메일을 무시하세요.\n");
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("이메일 인증 메일 발송 실패 (email={})", email, e);
        }
    }

    /**
     * 비밀번호 재설정 메일을 발송한다. sendVerificationEmail과 동일하게 실패해도 예외를 던지지
     * 않는다 — forgot-password 엔드포인트는 이메일 존재 여부를 응답으로 노출하지 않기 위해
     * 항상 200을 반환하므로, 여기서 던져도 호출부가 다르게 응답할 수 없다.
     */
    public void sendPasswordResetEmail(String email, String name, String token) {
        String resetUrl = frontendBaseUrl + "/reset-password?email=" + urlEncode(email) + "&token=" + urlEncode(token);
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(email);
            message.setSubject("[" + brandName + "] 비밀번호 재설정 안내");
            message.setText(
                    (name == null || name.isBlank() ? email : name) + "님, 비밀번호 재설정을 요청하셨습니다.\n\n"
                            + "아래 링크에서 새 비밀번호를 설정해주세요 (1시간 이내 유효):\n"
                            + resetUrl + "\n\n"
                            + "본인이 요청하지 않았다면 이 메일을 무시하세요.\n");
            mailSender.send(message);
        } catch (MailException e) {
            log.warn("비밀번호 재설정 메일 발송 실패 (email={})", email, e);
        }
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
