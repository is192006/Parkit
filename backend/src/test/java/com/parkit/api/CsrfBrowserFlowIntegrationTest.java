package com.parkit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The real browser CSRF flow over HTTP (ARCHITECTURE.md §2): any GET returns a
 * JavaScript-readable {@code XSRF-TOKEN} cookie, and state-changing requests
 * must echo it in {@code X-XSRF-TOKEN}. Uses a real server instead of MockMvc,
 * whose {@code csrf()} helper replaces the token repository in the shared context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "parkit.admin.email=" + AbstractApiIntegrationTest.ADMIN_EMAIL,
        "parkit.admin.password=" + AbstractApiIntegrationTest.ADMIN_PASSWORD,
        "parkit.admin.phone=9000000000"
})
class CsrfBrowserFlowIntegrationTest extends AbstractApiIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void cookieToHeaderTokenIsRequiredForStateChangingRequests() throws Exception {
        HttpResponse<String> first = http.send(
                HttpRequest.newBuilder(uri("/api/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String setCookie = first.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(setCookie).doesNotContainIgnoringCase("HttpOnly");
        String token = setCookie.substring("XSRF-TOKEN=".length(), setCookie.indexOf(';'));

        String body = """
                {"name":"X","email":"%s","password":"%s","phone":"9876543210"}
                """;

        assertThat(post(body.formatted(uniqueEmail(), PASSWORD), List.of()).statusCode()).isEqualTo(403);
        assertThat(post(body.formatted(uniqueEmail(), PASSWORD),
                List.of("Cookie", "XSRF-TOKEN=" + token, "X-XSRF-TOKEN", "forged")).statusCode()).isEqualTo(403);
        assertThat(post(body.formatted(uniqueEmail(), PASSWORD),
                List.of("Cookie", "XSRF-TOKEN=" + token, "X-XSRF-TOKEN", token)).statusCode()).isEqualTo(201);
    }

    @Test
    void loginSetsHttpOnlySessionCookie() throws Exception {
        String email = uniqueEmail();
        HttpResponse<String> csrf = http.send(
                HttpRequest.newBuilder(uri("/api/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String token = csrf.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0].split("=")[1];
        List<String> headers = List.of("Cookie", "XSRF-TOKEN=" + token, "X-XSRF-TOKEN", token);
        post("""
                {"name":"X","email":"%s","password":"%s","phone":"9876543210"}
                """.formatted(email, PASSWORD), headers);

        HttpResponse<String> login = http.send(HttpRequest.newBuilder(uri("/api/auth/login"))
                        .headers(headers.toArray(String[]::new))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String session = login.headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(session).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
    }

    private HttpResponse<String> post(String json, List<String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/api/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (!headers.isEmpty()) {
            b.headers(headers.toArray(String[]::new));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
