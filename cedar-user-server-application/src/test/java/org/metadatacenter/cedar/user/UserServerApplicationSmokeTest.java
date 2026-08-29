package org.metadatacenter.cedar.user;

import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.metadatacenter.config.environment.CedarEnvironmentSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Boots the real application through Dropwizard test support and exercises the wiring no
 * backend is needed for. This catches configuration and startup rot that a config-only test
 * cannot see.
 */
public class UserServerApplicationSmokeTest {

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

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + SERVER.getLocalPort() + path))
        .GET()
        .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void indexIsServed() throws Exception {
    HttpResponse<String> response = get("/");
    Assertions.assertEquals(200, response.statusCode());
    Assertions.assertTrue(response.body().contains("name"));
  }

  @Test
  public void protectedEndpointRejectsMissingCredentials() throws Exception {
    HttpResponse<String> response = get("/users/00000000-0000-0000-0000-000000000000");
    Assertions.assertEquals(401, response.statusCode());
  }

  @Test
  public void generatedOpenApiSpecificationIsServed() throws Exception {
    HttpResponse<String> response = get("/swagger-api/swagger.json");
    Assertions.assertEquals(200, response.statusCode());
    Assertions.assertTrue(response.body().contains("CEDAR User Server API"));
    Assertions.assertTrue(response.body().contains("/users/{id}/api-keys"));
  }

}
