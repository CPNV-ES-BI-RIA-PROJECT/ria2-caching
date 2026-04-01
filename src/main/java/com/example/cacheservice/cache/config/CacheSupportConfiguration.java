package com.example.cacheservice.cache.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheSupportConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
