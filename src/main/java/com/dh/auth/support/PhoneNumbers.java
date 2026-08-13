package com.dh.auth.support;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber;

/**
 * 휴대폰 번호를 E.164 정규형(+821012345678)으로 바꾸는 단일 창구.
 *
 * <p>국가별 정규식을 직접 들고 있으면 지원 국가가 늘 때마다, 그리고 각국 번호계획이 바뀔 때마다
 * 따라가야 하므로 libphonenumber에 위임한다. 저장/조회/중복검사는 전부 이 정규형을 쓴다 —
 * {@code 010-1234-5678}과 {@code +821012345678}이 서로 다른 값으로 저장되면 UNIQUE 제약이
 * 무의미해지기 때문이다.
 *
 * <p>입력 규칙:
 * <ul>
 *   <li>{@code +}로 시작하면 그대로 국제형으로 해석한다.</li>
 *   <li>아니면 요청 로케일에서 유추한 지역 → {@link #FALLBACK_REGION} 순으로 시도한다.
 *       기존 한국 사용자가 {@code 010-1234-5678}을 그대로 입력하는 동작을 깨지 않기 위한 것이고,
 *       해외 번호는 국가번호를 붙여야 한다(메시지에 안내).</li>
 * </ul>
 */
public final class PhoneNumbers {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    /**
     * 지원 로케일(ko/en/zh/ja) 중 지역을 하나로 단정할 수 있는 것만 매핑한다.
     * {@code en}은 특정 국가를 뜻하지 않으므로 일부러 뺐다 — 그 경우 아래 fallback이 쓰인다.
     */
    private static final Map<String, String> REGION_BY_LANGUAGE = Map.of(
            "ko", "KR",
            "ja", "JP",
            "zh", "CN");

    /** 로케일로 지역을 못 정할 때의 기본값. 서비스 주 이용자가 한국이라 KR로 둔다. */
    private static final String FALLBACK_REGION = "KR";

    private PhoneNumbers() {
    }

    /** 현재 요청 로케일을 기본 지역으로 삼아 정규화한다. */
    public static Optional<String> toE164(String raw) {
        return toE164(raw, defaultRegion(LocaleContextHolder.getLocale()));
    }

    /**
     * 유효한 휴대폰 번호면 E.164 정규형을, 아니면 {@link Optional#empty()}를 돌려준다.
     * 이미 E.164인 값을 다시 넣어도 같은 값이 나온다(멱등).
     */
    public static Optional<String> toE164(String raw, String defaultRegion) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String candidate = raw.trim();
        for (String region : regionsToTry(candidate, defaultRegion)) {
            Optional<String> formatted = parse(candidate, region);
            if (formatted.isPresent()) {
                return formatted;
            }
        }
        return Optional.empty();
    }

    /**
     * 정규화에 실패하면 예외를 던진다. 이미 DTO 검증({@link ValidPhoneNumber})을 통과한 값에만
     * 쓰는 용도라, 여기까지 와서 실패하면 검증을 건너뛴 경로가 있다는 뜻이다.
     */
    public static String requireE164(String raw) {
        return toE164(raw).orElseThrow(
                () -> new IllegalArgumentException("E.164로 정규화할 수 없는 전화번호입니다."));
    }

    /** {@code +} 국제형이면 지역 정보가 필요 없고, 아니면 기본 지역 → fallback 순으로 시도한다. */
    private static Set<String> regionsToTry(String candidate, String defaultRegion) {
        if (candidate.startsWith("+")) {
            // parse()는 국제형 입력에 한해 지역이 null이어도 되지만, 아래 Set에 null을 담지
            // 않으려고 fallback을 넣어둔다(국제형이면 어차피 무시된다).
            return Set.of(FALLBACK_REGION);
        }
        Set<String> regions = new LinkedHashSet<>();
        if (defaultRegion != null) {
            regions.add(defaultRegion);
        }
        regions.add(FALLBACK_REGION);
        return regions;
    }

    private static Optional<String> parse(String candidate, String region) {
        try {
            Phonenumber.PhoneNumber parsed = UTIL.parse(candidate, region);
            if (!UTIL.isValidNumber(parsed) || !isMobile(parsed)) {
                return Optional.empty();
            }
            return Optional.of(UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    /**
     * OTP를 받을 수 있어야 하므로 유선번호는 거른다. 다만 미국처럼 번호만으로 유선/휴대폰을
     * 구분할 수 없는 국가는 {@code FIXED_LINE_OR_MOBILE}로 나오므로 함께 허용한다.
     */
    private static boolean isMobile(Phonenumber.PhoneNumber parsed) {
        PhoneNumberType type = UTIL.getNumberType(parsed);
        return type == PhoneNumberType.MOBILE || type == PhoneNumberType.FIXED_LINE_OR_MOBILE;
    }

    private static String defaultRegion(Locale locale) {
        return locale == null ? null : REGION_BY_LANGUAGE.get(locale.getLanguage());
    }
}
