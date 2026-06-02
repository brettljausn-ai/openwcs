package org.openwcs.gateway;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Exercises the real edge-auth path (build.md §4.8, §12): the gateway runs as an OAuth2
 * resource server against a live Keycloak that has imported the canonical {@code openwcs}
 * realm, and a downstream "service" is a tiny echo server reached through a gateway route.
 *
 * <p>Verifies that (1) an unauthenticated request is rejected, (2) a JWT minted by the realm
 * is accepted and the authenticated identity is propagated downstream as
 * {@code X-Auth-User} / {@code X-Auth-Roles}, and (3) client-supplied identity headers are
 * stripped (anti-spoofing), even with a valid token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class GatewayAuthEndToEndTest {

    private static final String REALM = "openwcs";
    private static final String CLIENT_ID = "openwcs-web";

    @Container
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:25.0")
            .withExposedPorts(8080)
            .withEnv("KEYCLOAK_ADMIN", "admin")
            .withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(realmFile().getAbsolutePath()),
                    "/opt/keycloak/data/import/openwcs-realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/" + REALM).forPort(8080).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    /** Stands in for a downstream service so we can observe the forwarded identity headers. */
    static final HttpServer ECHO;

    static {
        try {
            ECHO = HttpServer.create(new InetSocketAddress(0), 0);
            ECHO.createContext("/", exchange -> {
                String user = exchange.getRequestHeaders().getFirst("X-Auth-User");
                String roles = exchange.getRequestHeaders().getFirst("X-Auth-Roles");
                byte[] body = ("user=" + user + ";roles=" + roles).getBytes(UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            ECHO.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static File realmFile() {
        // Gradle runs tests with the module dir as the working dir; the realm lives at repo root.
        return new File("../platform/keycloak/openwcs-realm.json").getAbsoluteFile();
    }

    private static String issuerUri() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + REALM;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("openwcs.security.enabled", () -> true);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", GatewayAuthEndToEndTest::issuerUri);
        // Route /api/iam/** at our echo server so we can see what the gateway forwards.
        registry.add("OPENWCS_URI_IAM", () -> "http://localhost:" + ECHO.getAddress().getPort());
    }

    @AfterAll
    static void stopEcho() {
        ECHO.stop(0);
    }

    @Autowired
    WebTestClient web;

    @Test
    void rejectsRequestWithoutToken() {
        web.get().uri("/api/iam/whoami").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void acceptsRealmTokenAndPropagatesIdentity() throws Exception {
        String token = passwordGrantToken("viewer", "viewer");

        web.get().uri("/api/iam/whoami")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("user=viewer");
                    assertThat(body).contains("VIEWER"); // realm role from the JWT's realm_access
                });
    }

    @Test
    void stripsClientSuppliedIdentityHeaders() throws Exception {
        String token = passwordGrantToken("viewer", "viewer");

        web.get().uri("/api/iam/whoami")
                .header("Authorization", "Bearer " + token)
                .header("X-Auth-User", "hacker")
                .header("X-Auth-Roles", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("user=viewer"); // the gateway overwrote the spoofed user
                    assertThat(body).doesNotContain("hacker");
                });
    }

    /** Resource Owner Password Credentials grant against the public {@code openwcs-web} client. */
    private static String passwordGrantToken(String username, String password) throws Exception {
        String form = "grant_type=password"
                + "&client_id=" + CLIENT_ID
                + "&username=" + username
                + "&password=" + password;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(issuerUri() + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, BodyHandlers.ofString());
        System.out.println("[token] status=" + response.statusCode() + " body=" + response.body());
        assertThat(response.statusCode()).as("token endpoint response: %s", response.body()).isEqualTo(200);
        JsonNode json = new ObjectMapper().readTree(response.body());
        return json.get("access_token").asText();
    }
}
