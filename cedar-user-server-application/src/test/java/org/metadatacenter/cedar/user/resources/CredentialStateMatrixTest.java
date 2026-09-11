package org.metadatacenter.cedar.user.resources;

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
import org.metadatacenter.config.environment.CedarEnvironmentVariableProvider;
import org.metadatacenter.id.CedarUserId;
import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.server.security.model.user.CedarUserApiKey;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.test.CredentialMatrix;
import org.metadatacenter.util.test.EmbeddedCedarNeo4j;
import org.metadatacenter.util.test.TestAuthUtil;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Which credentials are anybody at all, across the routes a credential reaches first.
 *
 * <p>The authorization matrices elsewhere in the estate ask who may do what, with an unauthenticated
 * caller as their only refusal. This one asks the question before it: a credential that exists and
 * should not work must be nobody, whatever it is being asked for. A disabled key authenticated for
 * as long as it did because no grid had a column for it.
 *
 * <p>It runs against the graph-backed user service rather than the in-memory one every other suite
 * installs. That is the whole point. The in-memory service read the enabled flag when the stored one
 * did not, so a matrix run against the double would have passed throughout.
 */
public class CredentialStateMatrixTest {

  static {
    EmbeddedCedarNeo4j.startAndRedirectEnvironment(Map.of(
        "CEDAR_USER_HTTP_PORT", "0",
        "CEDAR_USER_ADMIN_PORT", "0",
        "CEDAR_USER_STOP_PORT", "0",
        "CEDAR_REDIS_PERSISTENT_PORT", "1"));
  }

  public static final DropwizardTestSupport<UserServerConfiguration> SERVER =
      new DropwizardTestSupport<>(UserServerApplication.class, ResourceHelpers.resourceFilePath("test-config.yml"));

  private static String workingHeader;
  private static String user1Uuid;
  private static String disabledKey;
  private static String deletedKey;
  private static final String UNKNOWN_KEY = "00000000-0000-4000-8000-000000000000";

  @BeforeAll
  public static void oneTimeSetUp() throws Exception {
    SERVER.before();
    CedarConfig cedarConfig =
        CedarConfig.getInstance(CedarEnvironmentVariableProvider.getFor(SystemComponent.SERVER_USER));
    EmbeddedCedarNeo4j.seed(cedarConfig);
    workingHeader = TestAuthUtil.getTestUser1AuthHeader(cedarConfig);
    String user1Id = TestAuthUtil.getTestUser1(cedarConfig).getId();
    user1Uuid = user1Id.substring(user1Id.lastIndexOf('/') + 1);

    UserService users = CedarDataServices.getInstance().getNeoUserService();
    CedarUserId userId = CedarUserId.build(user1Id);
    disabledKey = storeKey(users, userId, "matrix-disabled-", false).getKey();

    CedarUserApiKey removed = storeKey(users, userId, "matrix-deleted-", true);
    deletedKey = removed.getKey();
    Assertions.assertFalse(users.deleteApiKey(userId, removed.getId()).isError());
  }

  private static CedarUserApiKey storeKey(UserService users, CedarUserId userId, String prefix, boolean enabled) {
    CedarUserApiKey key = new CedarUserApiKey();
    key.setId(UUID.randomUUID().toString());
    key.setKey(prefix + UUID.randomUUID());
    key.setServiceName("CEDAR");
    key.setDescription("Credential matrix fixture");
    key.setCreationDate(OffsetDateTime.now());
    key.setEnabled(enabled);
    Assertions.assertFalse(users.addApiKey(userId, key, 20).isError());
    return key;
  }

  @AfterAll
  public static void oneTimeTearDown() {
    SERVER.after();
  }

  @Test
  public void onlyALiveCredentialIsAnybody() {
    String userPath = "/users/" + user1Uuid;
    CredentialMatrix matrix = new CredentialMatrix("http://localhost:" + SERVER.getLocalPort());

    matrix.accepted("the user's own enabled key", workingHeader);
    matrix.refused("no credential at all", null);
    matrix.refused("a key nobody holds", "apiKey " + UNKNOWN_KEY);
    matrix.refused("a key its holder disabled", "apiKey " + disabledKey);
    matrix.refused("a key its holder deleted", "apiKey " + deletedKey);
    matrix.refused("the scheme with no key after it", "apiKey ");
    matrix.refused("a header in no scheme this server accepts", "Basic dXNlcjpwYXNzd29yZA==");

    matrix.when("GET", userPath).accepting(200);
    // The summary reads federated identities from Keycloak, which no test environment runs, and how
    // its absence shows depends on the host: a workstation that refuses the connection outright
    // gives 404 from the not-found branch, and a runner where the connect attempt fails gives 503.
    // Either way the credential reached the route, which is the far side of authentication and all
    // this row claims about the accepted cell. The refusals on it are the assertion that matters.
    matrix.when("GET", userPath + "/summary").accepting(200, 404, 503);
    matrix.when("PUT", userPath, "{\"uiPreferences.stylesheet\": \"credential-matrix\"}").accepting(200);
    matrix.when("POST", userPath + "/api-keys", "{\"description\": \"issued under the matrix\"}").accepting(201);

    matrix.verify();
  }
}
