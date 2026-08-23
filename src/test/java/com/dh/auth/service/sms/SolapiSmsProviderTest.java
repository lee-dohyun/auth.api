package com.dh.auth.service.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.request.MessageListRequest;
import net.nurigo.sdk.message.response.MessageListResponse;
import net.nurigo.sdk.message.service.DefaultMessageService;

/**
 * auth.api#34 — Solapi {@code sendOne()}의 접수(2000)를 전달 성공으로 착각하지 않기 위한
 * 지연 상태 확인({@code checkDelivery})만 좁게 검증한다.
 *
 * <p>{@code SolapiSmsProvider}는 생성자에서 실제 {@link DefaultMessageService}를 직접 만들기 때문에
 * (auth.api#30 교체 지점 설계와 별개 문제) 표준 생성자 주입으로는 벤더 응답을 흉내낼 수 없다.
 * {@code messageService} 필드를 리플렉션으로 목으로 바꿔치기하는 것이 이 클래스의 기존 구조를
 * 건드리지 않는 최소 방법이다. {@link SimpleMeterRegistry}는 실제 레지스트리라 카운터 증가를
 * 그대로 관측할 수 있다.
 */
class SolapiSmsProviderTest {

    private static final String MESSAGE_ID = "M4V20260823TEST0001";
    private static final String PHONE = "01099998888";

    private SolapiSmsProvider newProvider(SimpleMeterRegistry meterRegistry, DefaultMessageService messageService) {
        SolapiSmsProvider provider = new SolapiSmsProvider("", "", "01000000000", meterRegistry);
        ReflectionTestUtils.setField(provider, "messageService", messageService);
        return provider;
    }

    private static MessageListResponse responseWithStatus(String statusCode) {
        Message message = new Message();
        message.setMessageId(MESSAGE_ID);
        message.setStatusCode(statusCode);
        return new MessageListResponse(null, null, Map.of(MESSAGE_ID, message));
    }

    @Test
    @DisplayName("최종 전달 완료(4000)면 실패 카운터를 증가시키지 않는다")
    void checkDelivery_Complete_DoesNotIncrementFailureCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DefaultMessageService messageService = mock(DefaultMessageService.class);
        when(messageService.getMessageList(any(MessageListRequest.class))).thenReturn(responseWithStatus("4000"));
        SolapiSmsProvider provider = newProvider(meterRegistry, messageService);

        ReflectionTestUtils.invokeMethod(provider, "checkDelivery", MESSAGE_ID, PHONE);

        assertThat(meterRegistry.find("sms.send.failed").counter()).isNull();
    }

    @Test
    @DisplayName("통신사 결과가 실패 상태코드면 sms.send.failed{statusCode} 카운터를 남긴다")
    void checkDelivery_FailureStatusCode_IncrementsFailureCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DefaultMessageService messageService = mock(DefaultMessageService.class);
        // 실제 auth.api#34 인시던트에서 관측된 코드 — 번호도용문자차단서비스 가입 발신번호.
        when(messageService.getMessageList(any(MessageListRequest.class))).thenReturn(responseWithStatus("3113"));
        SolapiSmsProvider provider = newProvider(meterRegistry, messageService);

        ReflectionTestUtils.invokeMethod(provider, "checkDelivery", MESSAGE_ID, PHONE);

        assertThat(meterRegistry.get("sms.send.failed").tag("statusCode", "3113").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("결과 조회 자체가 실패해도 예외를 삼킨다 — 조회 오류를 발송 실패로 오인해 사용자 흐름에 영향을 주면 안 된다")
    void checkDelivery_LookupThrows_DoesNotPropagate() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DefaultMessageService messageService = mock(DefaultMessageService.class);
        when(messageService.getMessageList(any(MessageListRequest.class))).thenThrow(new RuntimeException("network down"));
        SolapiSmsProvider provider = newProvider(meterRegistry, messageService);

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(provider, "checkDelivery", MESSAGE_ID, PHONE))
                .doesNotThrowAnyException();
        assertThat(meterRegistry.find("sms.send.failed").counter()).isNull();
    }

    @Test
    @DisplayName("messageId를 응답에서 찾지 못하면 조용히 넘어간다")
    void checkDelivery_MessageNotFound_DoesNothing() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DefaultMessageService messageService = mock(DefaultMessageService.class);
        when(messageService.getMessageList(any(MessageListRequest.class)))
                .thenReturn(new MessageListResponse(null, null, Map.of()));
        SolapiSmsProvider provider = newProvider(meterRegistry, messageService);

        assertThatCode(() -> ReflectionTestUtils.invokeMethod(provider, "checkDelivery", MESSAGE_ID, PHONE))
                .doesNotThrowAnyException();
        assertThat(meterRegistry.find("sms.send.failed").counter()).isNull();
    }
}
