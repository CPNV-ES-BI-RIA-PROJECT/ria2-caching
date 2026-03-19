package com.example.cacheservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.example.cacheservice.cache.config.CacheOperationProperties;
import com.example.cacheservice.cache.config.CacheRedisProperties;

@SpringBootApplication
@EnableConfigurationProperties({ CacheRedisProperties.class, CacheOperationProperties.class })
public class CacheserviceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CacheserviceApplication.class, args);
	}

}
