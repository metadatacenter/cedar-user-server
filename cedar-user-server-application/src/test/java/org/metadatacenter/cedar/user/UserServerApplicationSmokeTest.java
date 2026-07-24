package org.metadatacenter.cedar.user;

import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Boots the real application through the Dropwizard test rule and exercises the wiring no
 * backend is needed for. This catches configuration and startup rot that a config-only test
 * cannot see.
 */
public class UserServerApplicationSmokeTest {

  @ClassRule
  public static final DropwizardAppRule<UserServerConfiguration> SERVER =
      new DropwizardAppRule<>(UserServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

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
    Assert.assertEquals(200, response.statusCode());
    Assert.assertTrue(response.body().contains("name"));
  }

  @Test
  public void protectedEndpointRejectsMissingCredentials() throws Exception {
    HttpResponse<String> response = get("/users/00000000-0000-0000-0000-000000000000");
    Assert.assertEquals(401, response.statusCode());
  }

}
