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
import org.metadatacenter.config.environment.CedarEnvironmentSource;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Endpoint tests for the user profile resource, running with no live backend: authentication and
 * the user store are served by the in-memory user service. The profile-ownership rules and the
 * uiPreferences patch semantics (shared with the Neo4j-backed service through UserServiceUtil)
 * are exercised through real HTTP requests against the booted application. The summary endpoint
 * is not covered here: it queries the Keycloak admin API and needs a live Keycloak.
 */
public class UsersResourceTest {

  static {
    // Must run before the test support boots the server, which reads the port env vars.
    // Alternate server ports, so the test instance never collides with a running dev server.
    Map<String, String> environment = new HashMap<>(CedarEnvironmentSource.getAll());
    environment.put("CEDAR_USER_HTTP_PORT", "19005");
    environment.put("CEDAR_USER_ADMIN_PORT", "19105");
    environment.put("CEDAR_USER_STOP_PORT", "19205");
    CedarEnvironmentSource.setOverride(environment);
  }

  public static final DropwizardTestSupport<UserServerConfiguration> SERVER =
      new DropwizardTestSupport<>(UserServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static CedarConfig cedarConfig;
  private static String authHeaderUser1;
  private static String user1Uuid;
  private static String user2Uuid;

  @BeforeAll
  public static void oneTimeSetUp() {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_USER);
    cedarConfig = CedarConfig.getInstance(environment);

    // Replace the Neo4j-backed user service for authentication and for the resource's own store
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    UsersResource.injectUserService(TestAuthUtil.getInMemoryUserService(cedarConfig));

    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    user1Uuid = lastSegment(TestAuthUtil.getTestUser1(cedarConfig).getId());
    user2Uuid = lastSegment(TestAuthUtil.getTestUser2(cedarConfig).getId());
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  private static String lastSegment(String id) {
    return id.substring(id.lastIndexOf('/') + 1);
  }

  private HttpResponse<String> request(String method, String uuid, String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + "/users/" + uuid))
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
  public void ownProfileIsServed() throws Exception {
    HttpResponse<String> response = request("GET", user1Uuid, null);
    Assertions.assertEquals(200, response.statusCode());
    JsonNode user = JsonMapper.MAPPER.readTree(response.body());
    Assertions.assertEquals("Test1", user.get("firstName").asText());
    Assertions.assertFalse(user.has("_id"), "The Mongo _id field must not be exposed");
  }

  @Test
  public void otherProfileReadIsForbidden() throws Exception {
    HttpResponse<String> response = request("GET", user2Uuid, null);
    Assertions.assertEquals(403, response.statusCode());
    Assertions.assertTrue(response.body().contains("readOtherProfileForbidden"));
  }

  @Test
  public void otherProfileUpdateIsForbidden() throws Exception {
    HttpResponse<String> response = request("PUT", user2Uuid, "{}");
    Assertions.assertEquals(403, response.statusCode());
  }

  @Test
  public void uiPreferencesCanBePatched() throws Exception {
    HttpResponse<String> response = request("PUT", user1Uuid, "{\"uiPreferences.stylesheet\": \"smoke-test\"}");
    Assertions.assertEquals(200, response.statusCode());
    JsonNode user = JsonMapper.MAPPER.readTree(response.body());
    Assertions.assertEquals("smoke-test", user.at("/uiPreferences/stylesheet").asText());
  }

  @Test
  public void modificationsOutsideUiPreferencesAreRejected() throws Exception {
    HttpResponse<String> response = request("PUT", user1Uuid, "{\"firstName\": \"Changed\"}");
    Assertions.assertEquals(400, response.statusCode());
  }

}
