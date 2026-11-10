package dev.swapnil.ledger;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole stack over real HTTP: Tomcat, JSON, controllers, services, JDBC, PostgreSQL.
 * Walks the story a client would: open two accounts, move money, retry, misuse the key, read balances.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class LedgerApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void openTransferRetryAndReadBalances() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String operating = openAccount("OPERATING-" + suffix, "ASSET", true);  // funding source: may go negative
        String settlement = openAccount("SETTLEMENT-" + suffix, "ASSET", false);
        String key = "pay-" + suffix;
        String transfer = """
                {"fromAccountId":"%s","toAccountId":"%s","amountMinor":150000,"currency":"INR","description":"sweep to settlement"}
                """.formatted(operating, settlement);

        // 1. first attempt writes the entry
        HttpResponse<String> first = send(post("/transfers", transfer).header("Idempotency-Key", key));
        assertThat(first.statusCode()).isEqualTo(201);
        String entryId = JsonPath.read(first.body(), "$.entryId");

        // 2. client times out and retries: same entry, nothing new written
        HttpResponse<String> retry = send(post("/transfers", transfer).header("Idempotency-Key", key));
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat((String) JsonPath.read(retry.body(), "$.entryId")).isEqualTo(entryId);

        // 3. same key, different amount: refused, not silently "succeeded"
        HttpResponse<String> misuse = send(post("/transfers", transfer.replace("150000", "999999"))
                .header("Idempotency-Key", key));
        assertThat(misuse.statusCode()).isEqualTo(409);
        assertThat(misuse.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/problem+json"));

        // 4. balances reflect exactly one transfer of 1,500.00
        assertThat(balanceOf(operating)).isEqualTo(-150_000L);   // asset credited: money left
        assertThat(balanceOf(settlement)).isEqualTo(150_000L);   // asset debited: money arrived
    }

    @Test
    void transferBetweenUnknownAccountsIs404() throws Exception {
        String body = """
                {"fromAccountId":"%s","toAccountId":"%s","amountMinor":100,"currency":"INR"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        HttpResponse<String> response = send(post("/transfers", body).header("Idempotency-Key", "k-" + UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(404);
    }

    @Test
    void duplicateAccountCodeIs409() throws Exception {
        String code = "DUP-" + UUID.randomUUID().toString().substring(0, 8);
        openAccount(code, "ASSET", false);

        HttpResponse<String> again = send(post("/accounts", accountJson(code, "ASSET", false)));

        assertThat(again.statusCode()).isEqualTo(409);
    }

    private String openAccount(String code, String type, boolean allowNegative) throws Exception {
        HttpResponse<String> response = send(post("/accounts", accountJson(code, type, allowNegative)));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Location")).isPresent();
        return JsonPath.read(response.body(), "$.id");
    }

    private long balanceOf(String accountId) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/accounts/" + accountId + "/balance")).GET());
        assertThat(response.statusCode()).isEqualTo(200);
        return ((Number) JsonPath.read(response.body(), "$.balanceMinor")).longValue();
    }

    private static String accountJson(String code, String type, boolean allowNegative) {
        return """
                {"code":"%s","name":"%s account","currency":"INR","type":"%s","allowNegative":%s}
                """.formatted(code, code, type, allowNegative);
    }

    private HttpRequest.Builder post(String path, String json) {
        return HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + env.getProperty("local.server.port") + path);
    }
}
