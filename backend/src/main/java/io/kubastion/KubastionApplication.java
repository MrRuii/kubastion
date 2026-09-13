package io.kubastion;

import io.kubastion.config.KubastionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(KubastionProperties.class)
public class KubastionApplication {

    public static void main(String[] args) {
        SpringApplication.run(KubastionApplication.class, args);
    }
}
