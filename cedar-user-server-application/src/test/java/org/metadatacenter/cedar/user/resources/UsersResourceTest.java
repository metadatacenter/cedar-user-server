package org.metadatacenter.cedar.user.resources;

import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.bridge.CedarDataServices;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    environment.put("CEDAR_NEO4J_HOST", "127.0.0.1");
    environment.put("CEDAR_NEO4J_BOLT_PORT", "1");
    CedarEnvironmentSource.setOverride(environment);
  }

  public static final DropwizardTestSupport<UserServerConfiguration> SERVER =
      new DropwizardTestSupport<>(UserServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  private static CedarConfig cedarConfig;
  private static String authHeaderUser1;
  private static String authHeaderUser2;
  private static String authHeaderAdmin;
  private static String user1Uuid;
  private static String user2Uuid;

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    Map<String, String> environment = CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_USER);
    cedarConfig = CedarConfig.getInstance(environment);

    // Replace the Neo4j-backed user service for authentication and for the resource's own store
    TestAuthUtil.installInMemoryUserService(cedarConfig);
    UsersResource.injectUserService(TestAuthUtil.getInMemoryUserService(cedarConfig));

    authHeaderUser1 = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    authHeaderUser2 = TestAuthUtil.getTestUser2AuthHeader(cedarConfig);
    authHeaderAdmin = TestAuthUtil.getAdminUserAuthHeader(cedarConfig);
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
  public void graphOutageReturnsSanitizedServiceUnavailable() throws Exception {
    // Authentication stays in memory, while the resource is briefly restored to the application's
    // real Neo4j service. This separates a dependency failure in the operation from a failure to
    // authenticate the test request.
    UsersResource.injectUserService(CedarDataServices.getInstance().getNeoUserService());
    try {
      HttpResponse<String> response = request(
          "PUT", user1Uuid, "{\"uiPreferences.stylesheet\": \"unavailable\"}");

      Assertions.assertEquals(503, response.statusCode(), response.body());
      JsonNode error = JsonMapper.MAPPER.readTree(response.body());
      Assertions.assertEquals("SERVICE_UNAVAILABLE", error.path("status").asText(), response.body());
      Assertions.assertEquals("Neo4j is unavailable", error.path("message").asText(), response.body());
      Assertions.assertTrue(error.path("originalException").isMissingNode()
          || error.path("originalException").isNull(), response.body());
      Assertions.assertTrue(error.path("sourceException").isMissingNode()
          || error.path("sourceException").isNull(), response.body());
      Assertions.assertFalse(response.body().contains("127.0.0.1"), response.body());
    } finally {
      UsersResource.injectUserService(TestAuthUtil.getInMemoryUserService(cedarConfig));
    }
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

  private HttpResponse<String> get(String path, String authHeader) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .GET()
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * The summary is not public among logged-in users. It carries each federated identity provider and
   * the account id held there — the user's identifier at Google or ORCID — and this endpoint alone in
   * the resource asked nothing about who was calling.
   *
   * <p>Refused before Keycloak is consulted, which is why this runs without one.
   */
  @Test
  public void otherUserSummaryIsForbidden() throws Exception {
    HttpResponse<String> response = get("/users/" + user2Uuid + "/summary", authHeaderUser1);
    Assertions.assertEquals(403, response.statusCode(), response.body());
    Assertions.assertTrue(response.body().contains("readOtherProfileForbidden"), response.body());
  }

  /**
   * A user administrator still reads any summary, which is what keeps provenance display names
   * working: UserSummaryCache resolves them for arbitrary users as the built-in admin. Asserted
   * through an unknown id, so the answer proves the caller passed the authorization gate and was
   * stopped by the lookup rather than by permission — and so the assertion needs no Keycloak.
   *
   * <p>USER_READ belongs to the userAdministrator role, which cedar-main.yml grants to builtInAdmin
   * and not to a normal user.
   */
  @Test
  public void aUserAdministratorReachesTheLookupAndAnUnknownUserIsNotFound() throws Exception {
    HttpResponse<String> response =
        get("/users/00000000-0000-0000-0000-000000000000/summary", authHeaderAdmin);
    Assertions.assertEquals(404, response.statusCode(), response.body());
    Assertions.assertTrue(response.body().contains("userNotFound"), response.body());
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    return send(method, path, body, authHeaderUser1);
  }

  private HttpResponse<String> send(String method, String path, String body, String authHeader) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .header("Authorization", authHeader)
        .header("Content-Type", "application/json");
    builder.method(method, body == null
        ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertForbiddenForUser2(String method, String path, String body) throws Exception {
    HttpResponse<String> response = send(method, path, body, authHeaderUser2);
    Assertions.assertEquals(403, response.statusCode(), method + " " + path + ": " + response.body());
  }

  @Test
  public void ordinaryUserCanNotAccessAnotherUsersIdScopedEndpoints() {
    String user1Path = "/users/" + user1Uuid;
    Assertions.assertAll(
        () -> assertForbiddenForUser2("GET", user1Path, null),
        () -> assertForbiddenForUser2("PUT", user1Path, "{}"),
        () -> assertForbiddenForUser2("GET", user1Path + "/summary", null),
        () -> assertForbiddenForUser2("POST", user1Path + "/api-keys", "{}"),
        () -> assertForbiddenForUser2("POST", user1Path + "/api-keys/not-the-users-key/regenerate", null),
        () -> assertForbiddenForUser2("DELETE", user1Path + "/api-keys/not-the-users-key", null));
  }

  private static List<String> keyValues(String responseBody) throws Exception {
    List<String> values = new ArrayList<>();
    for (JsonNode key : JsonMapper.MAPPER.readTree(responseBody).get("apiKeys")) {
      values.add(key.get("key").asText());
    }
    return values;
  }

  private String ownKeysPath() {
    return "/users/" + user1Uuid + "/api-keys";
  }

  /**
   * The key these tests authenticate with. It is the user's only seeded key and the auth header was
   * built from it once, so no test may delete or regenerate it: doing so revokes the credential the
   * rest of the class depends on. Every test below either adds a key or acts on one it added.
   */
  private static String authApiKey() {
    return authHeaderUser1.substring(authHeaderUser1.lastIndexOf(' ') + 1);
  }

  @Test
  public void anApiKeyIsCreatedAndListedAgainstTheUser() throws Exception {
    List<String> before = keyValues(send("GET", "/users/" + user1Uuid, null).body());

    HttpResponse<String> created = send("POST", ownKeysPath(), "{\"description\": \"a test key\"}");
    Assertions.assertEquals(200, created.statusCode(), created.body());

    List<String> after = keyValues(created.body());
    Assertions.assertEquals(before.size() + 1, after.size(), created.body());
    Assertions.assertTrue(after.containsAll(before), "the existing keys must survive: " + created.body());

    // The key the caller was handed is the key the store holds. The change is applied to the stored
    // list now, rather than to the copy the request arrived with and wrote back whole.
    List<String> readBack = keyValues(send("GET", "/users/" + user1Uuid, null).body());
    Assertions.assertEquals(after, readBack, "the created key must be present on a fresh read");
  }

  @Test
  public void regeneratingAKeyReplacesItsValueAndKeepsTheCount() throws Exception {
    List<String> before = keyValues(send("POST", ownKeysPath(), "{\"description\": \"to be rotated\"}").body());
    String target = before.get(before.size() - 1);
    Assertions.assertNotEquals(authApiKey(), target, "the test must not rotate its own credential");

    HttpResponse<String> rotated = send("POST", ownKeysPath() + "/" + target + "/regenerate", null);
    Assertions.assertEquals(200, rotated.statusCode(), rotated.body());

    List<String> after = keyValues(rotated.body());
    Assertions.assertEquals(before.size(), after.size(), rotated.body());
    Assertions.assertFalse(after.contains(target), "the old value must be revoked: " + rotated.body());
  }

  @Test
  public void anUnknownKeyIsNotFoundAndTheValueIsNotEchoed() throws Exception {
    HttpResponse<String> response = send("DELETE", ownKeysPath() + "/nosuchkeyvalue", null);
    Assertions.assertEquals(404, response.statusCode(), response.body());
    Assertions.assertFalse(response.body().contains("nosuchkeyvalue"),
        "the supplied key value is a secret and must not come back: " + response.body());
  }

  /**
   * Reduced to a single enabled key, the delete is refused — which is also why this test can run at
   * all: the key it finally aims at is the one it authenticates with, and the refusal leaves it
   * intact.
   */
  @Test
  public void theLastEnabledKeyCanNotBeDeleted() throws Exception {
    String authKey = authApiKey();
    for (String key : keyValues(send("GET", "/users/" + user1Uuid, null).body())) {
      if (!key.equals(authKey)) {
        Assertions.assertEquals(200, send("DELETE", ownKeysPath() + "/" + key, null).statusCode());
      }
    }

    HttpResponse<String> last = send("DELETE", ownKeysPath() + "/" + authKey, null);
    Assertions.assertEquals(400, last.statusCode(), last.body());
    Assertions.assertEquals(List.of(authKey), keyValues(send("GET", "/users/" + user1Uuid, null).body()),
        "the refused delete must have left the key in place");
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
