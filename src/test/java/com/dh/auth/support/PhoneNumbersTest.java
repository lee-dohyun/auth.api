package com.dh.auth.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * 전화번호 정규화 규칙 고정. 여기가 흔들리면 저장된 번호 표기가 갈라지고(UNIQUE·인증 이력 조회가
 * 새고) 해외 가입이 다시 막히므로, 국가별 케이스를 명시적으로 박아둔다.
 */
class PhoneNumbersTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @ParameterizedTest
    @CsvSource({
            "010-1234-5678,   +821012345678",
            "01012345678,     +821012345678",
            "'010 1234 5678', +821012345678",
            "+821012345678,   +821012345678",
            "+82 10-1234-5678,+821012345678",
    })
    void 한국_번호는_표기와_무관하게_같은_E164로_모인다(String raw, String expected) {
        assertThat(PhoneNumbers.toE164(raw, "KR")).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "+819012345678,  +819012345678",
            "+8613123456789, +8613123456789",
            "+14155552671,   +14155552671",
            "+447911123456,  +447911123456",
    })
    void 국제형_입력은_기본_지역과_무관하게_받는다(String raw, String expected) {
        assertThat(PhoneNumbers.toE164(raw, "KR")).contains(expected);
    }

    @Test
    void 요청_로케일이_국내표기의_기본_지역을_정한다() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        assertThat(PhoneNumbers.toE164("090-1234-5678")).contains("+819012345678");

        LocaleContextHolder.setLocale(Locale.CHINESE);
        assertThat(PhoneNumbers.toE164("131 2345 6789")).contains("+8613123456789");
    }

    /** en은 특정 국가를 뜻하지 않으므로 KR fallback이 걸린다 — 한국 거주 영어 사용자를 막지 않기 위한 것. */
    @Test
    void 지역을_단정할_수_없는_로케일은_KR로_떨어진다() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(PhoneNumbers.toE164("010-1234-5678")).contains("+821012345678");
    }

    /** 로케일 기본 지역에서 실패해도 KR로 한 번 더 시도한다(ja 화면을 쓰는 한국 번호 사용자 등). */
    @Test
    void 기본_지역에서_실패하면_KR로_재시도한다() {
        assertThat(PhoneNumbers.toE164("010-1234-5678", "JP")).contains("+821012345678");
    }

    @Test
    void OTP를_받을_수_없는_유선번호는_거부한다() {
        assertThat(PhoneNumbers.toE164("02-123-4567", "KR")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2125550123",       // 국가번호 없는 해외 번호 — +1을 붙여야 한다
            "+8210",            // 너무 짧음
            "010-1234-56789",   // 자릿수 초과
            "abcdefg",
            "+",
    })
    void 잘못된_번호는_빈값이다(String raw) {
        assertThat(PhoneNumbers.toE164(raw, "KR")).isEmpty();
    }

    /**
     * 예전 {@code ^01[0-9]-?\d{3,4}-?\d{4}$} 정규식이 받아주던 국내 대역(010 10자리, 01X 구대역)은
     * libphonenumber도 유효로 보므로 그대로 통과한다 — 이번 변경으로 국내 사용자가 새로 막히는 일은 없다.
     * (01X 구대역은 2G 종료로 실사용은 끝났지만, 좁히는 건 이 작업 범위 밖이라 기존 동작을 유지한다.)
     */
    @ParameterizedTest
    @CsvSource({
            "010-123-4567,  +82101234567",
            "019-1234-5678, +821912345678",
    })
    void 기존_정규식이_받아주던_국내_번호는_계속_통과한다(String raw, String expected) {
        assertThat(PhoneNumbers.toE164(raw, "KR")).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 빈값은_빈값이다(String raw) {
        assertThat(PhoneNumbers.toE164(raw, "KR")).isEmpty();
    }

    @Test
    void requireE164는_검증을_건너뛴_경로를_바로_드러낸다() {
        assertThatThrownBy(() -> PhoneNumbers.requireE164("02-123-4567"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
