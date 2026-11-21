package com.copymylife.copymylife.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Service
public class EmailService {

    @Value("${MAILJET_API_KEY}")
    private String mailjetApiKey;

    @Value("${MAILJET_SECRET_KEY}")
    private String mailjetSecretKey;

    @Value("${MAILJET_SENDER_EMAIL}")
    private String senderEmail;

    @Value("${APP_BASE_URL:http://localhost:8080}")
    private String appBaseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public void sendPasswordResetEmail(String toEmail, String token) {

        String resetLink =
                appBaseUrl +
                        "/reset-password.html?token=" +
                        URLEncoder.encode(token, StandardCharsets.UTF_8);

        String emailText =
                "Hello!\n\n" +
                        "We received a request to reset your CopyMyLife password.\n\n" +
                        "Click the link below to reset your password:\n" +
                        resetLink + "\n\n" +
                        "This link expires in 1 hour.\n\n" +
                        "If you did not request this, you can ignore this email.\n\n" +
                        "CopyMyLife";

        String body = """
                {
                  "Messages": [
                    {
                      "From": {
                        "Email": "%s",
                        "Name": "CopyMyLife"
                      },
                      "To": [
                        {
                          "Email": "%s"
                        }
                      ],
                      "Subject": "CopyMyLife - Reset your password",
                      "TextPart": "%s"
                    }
                  ]
                }
                """.formatted(
                escapeJson(senderEmail),
                escapeJson(toEmail),
                escapeJson(emailText)
        );

        String auth = Base64.getEncoder().encodeToString(
                (mailjetApiKey + ":" + mailjetSecretKey)
                        .getBytes(StandardCharsets.UTF_8)
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mailjet.com/v3.1/send"))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

            if (response.statusCode() < 200 ||
                    response.statusCode() >= 300) {

                throw new RuntimeException(
                        "Mailjet error: " +
                                response.statusCode() +
                                " - " +
                                response.body()
                );
            }

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Failed to send reset email",
                    e
            );
        }
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}