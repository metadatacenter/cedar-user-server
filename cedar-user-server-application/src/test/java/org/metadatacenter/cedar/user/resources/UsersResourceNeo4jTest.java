package org.metadatacenter.cedar.user.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.cedar.user.UserServerApplication;
import org.metadatacenter.cedar.user.UserServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The real-storage variant of the user profile tests: nothing is mocked. Authentication resolves
 * the API key through the Neo4j-backed user service against the in-process graph, and the
 * uiPreferences patch round-trips through the real Cypher layer. The in-memory variant
 * (UsersResourceTest) covers the same rules faster; this class proves the storage path.
 */
public class UsersResourceNeo4jTest {

  static {
    // Must run before the test support boots the server, which reads the Neo4j env vars.
    // Redis is redirected to a dead port: queue writes are best-effort, and this enforces that
    // no endpoint under test ever depends on a live Redis. Alternate server ports, so the test
    // instance never collides with a running dev server.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_USER_HTTP_PORT", "19005",
        "CEDAR_USER_ADMIN_PORT", "19105",
        "CEDAR_USER_STOP_PORT", "19205",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<UserServerConfiguration> SERVER =
      new DropwizardTestSupport<>(UserServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static String authHeaderUser1;
  private static String user1Uuid;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_USER);
    CedarConfig cedarConfig = CedarConfig.getInstance(environment);

    // No in-memory user service here: the application's own Neo4j-backed service resolves the
    // seeded users from the embedded graph
    EmbeddedCedarNeo4j.seed(cedarConfig);

    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    String user1Id = TestAuthUtil.getTestUser1(cedarConfig).getId();
    user1Uuid = user1Id.substring(user1Id.lastIndexOf('/') + 1);
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  private HttpResponse<String> request(String method, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/users/" + user1Uuid))
        .header("Authorization", authHeaderUser1)
        .header("Content-Type", "application/json");
    if (body == null) {
      builder.GET();
    } else {
      builder.method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void ownProfileIsServedFromTheGraph() throws Exception {
    HttpResponse<String> response = request("GET", null);
    Assertions.assertEquals(200, response.statusCode());
    Assertions.assertEquals("Test1", JsonMapper.MAPPER.readTree(response.body()).get("firstName").asText());
  }

  @Test
  public void uiPreferencesPatchRoundTripsThroughTheGraph() throws Exception {
    HttpResponse<String> updated = request("PUT", "{\"uiPreferences.stylesheet\": \"graph-test\"}");
    Assertions.assertEquals(200, updated.statusCode());

    HttpResponse<String> readBack = request("GET", null);
    Assertions.assertEquals("graph-test",
        JsonMapper.MAPPER.readTree(readBack.body()).at("/uiPreferences/stylesheet").asText());
  }

  @Test
  public void modificationsOutsideUiPreferencesAreRejected() throws Exception {
    HttpResponse<String> response = request("PUT", "{\"firstName\": \"Changed\"}");
    Assertions.assertEquals(400, response.statusCode());
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeaderUser1)
        .header("Content-Type", "application/json");
    builder.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static List<String> keyValues(String responseBody) throws Exception {
    List<String> values = new ArrayList<>();
    for (JsonNode key : JsonMapper.MAPPER.readTree(responseBody).get("apiKeys")) {
      values.add(key.get("key").asText());
    }
    return values;
  }

  private String keysPath() {
    return "/users/" + user1Uuid + "/api-keys";
  }

  /**
   * The key this class authenticates with. It is seeded as the user's only key and the auth header
   * was built from it once, so no test may delete or regenerate it.
   */
  private static String authApiKey() {
    return authHeaderUser1.substring(authHeaderUser1.lastIndexOf(' ') + 1);
  }

  /**
   * The key operations against the real Cypher layer. The in-memory variant proves the endpoint
   * rules; this proves that the transaction which reads the stored keys and writes them back in one
   * step does what the rules expect.
   */
  @Test
  public void apiKeysAreCreatedRotatedAndRemovedThroughTheGraph() throws Exception {
    HttpResponse<String> created = send("POST", keysPath(), "{\"description\": \"graph lifecycle\"}");
    Assertions.assertEquals(200, created.statusCode(), created.body());
    List<String> afterCreate = keyValues(created.body());
    String target = afterCreate.get(afterCreate.size() - 1);
    Assertions.assertNotEquals(authApiKey(), target, "the test must not act on its own credential");

    HttpResponse<String> rotated = send("POST", keysPath() + "/" + target + "/regenerate", null);
    Assertions.assertEquals(200, rotated.statusCode(), rotated.body());
    List<String> afterRotate = keyValues(rotated.body());
    Assertions.assertEquals(afterCreate.size(), afterRotate.size(), rotated.body());
    Assertions.assertFalse(afterRotate.contains(target), "the rotated value must be gone: " + rotated.body());

    String rotatedValue = afterRotate.stream().filter(k -> !k.equals(authApiKey())).findFirst().orElseThrow();
    HttpResponse<String> deleted = send("DELETE", keysPath() + "/" + rotatedValue, null);
    Assertions.assertEquals(200, deleted.statusCode(), deleted.body());
    Assertions.assertFalse(keyValues(deleted.body()).contains(rotatedValue), deleted.body());

    // Every remaining key but the credential goes, so the refusal below is asked of a user who holds
    // exactly one. Other tests in this class add keys and the order between them is not fixed, so the
    // state has to be established here rather than assumed.
    for (String key : keyValues(send("GET", "/users/" + user1Uuid, null).body())) {
      if (!key.equals(authApiKey())) {
        Assertions.assertEquals(200, send("DELETE", keysPath() + "/" + key, null).statusCode());
      }
    }

    // Down to the credential itself, which must be refused rather than leaving no working key — and
    // the refusal is what lets this class keep authenticating afterwards.
    HttpResponse<String> last = send("DELETE", keysPath() + "/" + authApiKey(), null);
    Assertions.assertEquals(400, last.statusCode(), last.body());
    Assertions.assertEquals(List.of(authApiKey()), keyValues(send("GET", "/users/" + user1Uuid, null).body()),
        "the refused delete must have left the credential in place");
  }

  /**
   * Every concurrently created key survives.
   *
   * <p>This is the property the transaction buys. Each request used to read the user at
   * authentication time, append to that copy, and write the whole user back, so two creations that
   * overlapped both wrote a list built from the same starting point and the later write dropped the
   * earlier key — while its caller had already been handed it in a 200 and would find it
   * authenticated nothing. The keys are now read and written inside one transaction per request, and
   * the writes serialize on the user node.
   */
  @Test
  public void concurrentlyCreatedKeysAllSurvive() throws Exception {
    int concurrent = 6;
    List<String> beforeKeys = keyValues(send("GET", "/users/" + user1Uuid, null).body());

    ExecutorService pool = Executors.newFixedThreadPool(concurrent);
    CountDownLatch releaseTogether = new CountDownLatch(1);
    List<Future<HttpResponse<String>>> responses = new ArrayList<>();
    try {
      for (int i = 0; i < concurrent; i++) {
        int n = i;
        responses.add(pool.submit(() -> {
          releaseTogether.await();
          return send("POST", keysPath(), "{\"description\": \"concurrent " + n + "\"}");
        }));
      }
      releaseTogether.countDown();
      for (Future<HttpResponse<String>> response : responses) {
        Assertions.assertEquals(200, response.get(30, TimeUnit.SECONDS).statusCode());
      }
    } finally {
      pool.shutdownNow();
    }

    List<String> afterKeys = keyValues(send("GET", "/users/" + user1Uuid, null).body());
    Assertions.assertEquals(beforeKeys.size() + concurrent, afterKeys.size(),
        "every concurrently created key should be stored, none overwritten: " + afterKeys);
    Assertions.assertTrue(afterKeys.containsAll(beforeKeys), "the keys held beforehand must survive");
  }

}
