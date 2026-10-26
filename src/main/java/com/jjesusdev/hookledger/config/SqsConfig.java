package com.jjesusdev.hookledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

@Configuration
public class SqsConfig {

    /**
     * Building the client does not open a connection. The endpoint override exists for LocalStack;
     * in AWS it stays empty and the SDK resolves the regional endpoint.
     */
    @Bean(destroyMethod = "close")
    SqsClient sqsClient(HookledgerProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.sqs().region()));
        if (!properties.sqs().endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.sqs().endpoint()));
        }
        return builder.build();
    }
}
