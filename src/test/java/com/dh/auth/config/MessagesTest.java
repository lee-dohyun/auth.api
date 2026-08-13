package com.dh.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 번역 누락은 컴파일도 런타임도 안 잡아준다 — 키가 빠진 로케일로 요청이 들어와야
 * 그제서야 기본 번들(한국어)이 튀어나온다. 그래서 여기서 키 집합을 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MessagesTest {

    private static final List<String> TRANSLATED_BUNDLES = List.of("en", "zh", "ja");

    @Autowired
    private Messages messages;

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh", "ja"})
    void 번역_번들은_기본_번들과_키_집합이_같다(String language) throws IOException {
        Set<String> baseKeys = load("messages.properties").stringPropertyNames();
        Set<String> translatedKeys = load("messages_" + language + ".properties").stringPropertyNames();

        assertThat(translatedKeys)
                .as("messages_%s.properties에 빠진 키", language)
                .containsExactlyInAnyOrderElementsOf(baseKeys);
    }

    @Test
    void 로케일별로_다른_문구가_나온다() {
        Locale.setDefault(Locale.KOREAN);

        assertThat(resolve(Locale.KOREAN)).isEqualTo("인증번호가 일치하지 않습니다.");
        assertThat(resolve(Locale.ENGLISH)).isEqualTo("The verification code does not match.");
        assertThat(resolve(Locale.CHINESE)).isEqualTo("验证码不正确。");
        assertThat(resolve(Locale.JAPANESE)).isEqualTo("認証番号が一致しません。");
    }

    /** 지원하지 않는 언어(독일어 등)는 서버 JVM 로케일이 아니라 기본 번들로 떨어져야 한다. */
    @Test
    void 지원하지_않는_로케일은_기본_번들을_쓴다() {
        assertThat(resolve(Locale.GERMAN)).isEqualTo("인증번호가 일치하지 않습니다.");
    }

    @Test
    void 메시지_인자가_치환된다() {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            assertThat(messages.get("otp.cooldown", 60L)).isEqualTo("You can request a new code in 60 seconds.");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    private String resolve(Locale locale) {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
        try {
            return messages.get("otp.mismatch");
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    private Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("%s를 찾을 수 없음", resource).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    void 지원_로케일_목록과_번들_파일이_일치한다() {
        assertThat(LocaleConfig.SUPPORTED_LOCALES).hasSize(TRANSLATED_BUNDLES.size() + 1);
        assertThat(LocaleConfig.SUPPORTED_LOCALES).contains(LocaleConfig.DEFAULT_LOCALE);
    }
}
