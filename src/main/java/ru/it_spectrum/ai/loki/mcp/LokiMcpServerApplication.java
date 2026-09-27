package ru.it_spectrum.ai.loki.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Locale;

@SpringBootApplication
public class LokiMcpServerApplication {

    public static void main(String[] args) {
        // Library messages that reach the model, such as input schema errors, stay in English on any system locale.
        Locale.setDefault(Locale.ENGLISH);
        SpringApplication.run(LokiMcpServerApplication.class, args);
    }
}
