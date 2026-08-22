package com.dh.auth.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * order.api 의 내부 집계 API 를 호출한다. 클러스터 내부망 전용이라 게이트웨이를 거치지 않는다.
 *
 * <p>주문 데이터를 auth.api 가 직접 읽지 않는 이유: 두 서비스가 같은 테이블에 묶이면 order.api 의
 * 스키마 변경이 auth.api 를 깨뜨린다. 집계는 데이터를 가진 쪽이 한다.
 */
@Component
public class OrderApiClient {

    private static final Logger log = LoggerFactory.getLogger(OrderApiClient.class);

    private final RestClient restClient;

    public OrderApiClient(
            RestClient.Builder builder,
            @Value("${order-api.base-url:http://order-api.customer.svc.cluster.local:8080}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public record PurchaseSummary(String customerId, BigDecimal confirmedAmount) {
    }

    /**
     * {@code since} 이후 구매확정 금액을 회원(Keycloak sub)별로 합산해 온다.
     *
     * <p><b>구매확정이 없는 회원은 응답에 아예 없다.</b> 호출부가 0원으로 다뤄야 한다 —
     * 그래야 강등이 동작한다.
     *
     * @throws OrderApiUnavailableException 호출에 실패하면. 산정 배치는 부분 결과로 등급을
     *         바꾸면 안 된다 — 절반만 반영되면 나머지 회원이 부당하게 강등된다.
     */
    public Map<String, BigDecimal> fetchConfirmedPurchases(LocalDateTime since) {
        String uri = UriComponentsBuilder.fromPath("/internal/purchase-summary")
                .queryParam("since", since.toString())
                .build()
                .toUriString();
        try {
            List<PurchaseSummary> rows = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PurchaseSummary>>() {});
            if (rows == null) {
                throw new OrderApiUnavailableException("order.api 응답 본문이 비어 있습니다.");
            }
            return rows.stream().collect(Collectors.toMap(
                    PurchaseSummary::customerId, PurchaseSummary::confirmedAmount, (a, b) -> a));
        } catch (OrderApiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("order.api 구매확정액 집계 호출 실패. since={}", since, e);
            throw new OrderApiUnavailableException("order.api 호출에 실패했습니다.", e);
        }
    }

    public static class OrderApiUnavailableException extends RuntimeException {
        public OrderApiUnavailableException(String message) {
            super(message);
        }

        public OrderApiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
