package org.metadatacenter.cedar.user.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.metadatacenter.cedar.user.UserServerApplication;
import org.metadatacenter.cedar.user.UserServerConfiguration;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.test.TestAuthUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Endpoint tests for the user profile resource, running with no live backend: authentication and
 * the user store are served by the in-memory user service. The profile-ownership rules and the
 * uiPreferences patch semantics (shared with the Neo4j-backed service through UserServiceUtil)
 * are exercised through real HTTP requests against the booted application. The summary endpoint
 * is not covered here: it queries the Keycloak admin API and needs a live Keycloak.
 */
public class UsersResourceTest {

  @ClassRule
  public static final DropwizardAppRule<UserServerConfiguration> SERVER =
      new DropwizardAppRule<>(UserServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static CedarConfig cedarConfig;
  private static String authHeaderUser1;
  private static String user1Uuid;
  private static String user2Uuid;

  @BeforeClass
  public static void oneTimeSetUp() {
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_USER);
    cedarConfig = CedarConfig.getInstance(environment);

    // Replace the Neo4j-backed user service for authentication and for the resource's own store
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    UsersResource.injectUserService(TestAuthUtil.getInMemoryUserService(cedarConfig));

    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    user1Uuid = lastSegment(TestAuthUtil.getTestUser1(cedarConfig).getId());
    user2Uuid = lastSegment(TestAuthUtil.getTestUser2(cedarConfig).getId());
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
    Assert.assertEquals(200, response.statusCode());
    JsonNode user = JsonMapper.MAPPER.readTree(response.body());
    Assert.assertEquals("Test1", user.get("firstName").asText());
    Assert.assertFalse("The Mongo _id field must not be exposed", user.has("_id"));
  }

  @Test
  public void otherProfileReadIsForbidden() throws Exception {
    HttpResponse<String> response = request("GET", user2Uuid, null);
    Assert.assertEquals(403, response.statusCode());
    Assert.assertTrue(response.body().contains("readOtherProfileForbidden"));
  }

  @Test
  public void otherProfileUpdateIsForbidden() throws Exception {
    HttpResponse<String> response = request("PUT", user2Uuid, "{}");
    Assert.assertEquals(403, response.statusCode());
  }

  @Test
  public void uiPreferencesCanBePatched() throws Exception {
    HttpResponse<String> response = request("PUT", user1Uuid, "{\"uiPreferences.stylesheet\": \"smoke-test\"}");
    Assert.assertEquals(200, response.statusCode());
    JsonNode user = JsonMapper.MAPPER.readTree(response.body());
    Assert.assertEquals("smoke-test", user.at("/uiPreferences/stylesheet").asText());
  }

  @Test
  public void modificationsOutsideUiPreferencesAreRejected() throws Exception {
    HttpResponse<String> response = request("PUT", user1Uuid, "{\"firstName\": \"Changed\"}");
    Assert.assertEquals(400, response.statusCode());
  }

}
