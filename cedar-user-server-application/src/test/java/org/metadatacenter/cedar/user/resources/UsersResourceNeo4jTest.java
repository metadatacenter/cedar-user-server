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
import java.util.Map;

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
    // no endpoint under test ever depends on a live Redis.
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of("CEDAR_REDIS_PERSISTENT_PORT", "1"));
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

}
