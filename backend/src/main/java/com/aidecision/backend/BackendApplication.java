package com.aidecision.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.aidecision.backend.config.AzureOpenAiProperties;
import com.aidecision.backend.config.AzureSearchProperties;

@SpringBootApplication
@EnableConfigurationProperties({ AzureOpenAiProperties.class, AzureSearchProperties.class })
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
