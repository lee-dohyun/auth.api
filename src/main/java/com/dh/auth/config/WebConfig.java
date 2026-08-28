package com.dh.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig {

	@Value("${app.cors-allowed-origin-pattern}")
	private String corsAllowedOriginPattern;

	private final AdminAuthInterceptor adminAuthInterceptor;

	public WebConfig(AdminAuthInterceptor adminAuthInterceptor) {
		this.adminAuthInterceptor = adminAuthInterceptor;
	}

	@Bean
	WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {
			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/**").allowedOriginPatterns(corsAllowedOriginPattern)
						.allowedMethods("GET", "POST", "OPTIONS").allowCredentials(true);
			}

			/**
			 * 관리 API 는 전부 인터셉터를 지난다. 여기에 등록하지 않은 경로는 인터셉터가 아예
			 * 돌지 않아 무방비가 되므로, {@code /api/admin/**} 을 통째로 걸고
			 * 역할 매핑은 {@link AdminAuthInterceptor#PATH_ROLES} 에서 관리한다.
			 */
			@Override
			public void addInterceptors(InterceptorRegistry registry) {
				registry.addInterceptor(adminAuthInterceptor).addPathPatterns("/api/admin/**");
			}
		};
	}
}
