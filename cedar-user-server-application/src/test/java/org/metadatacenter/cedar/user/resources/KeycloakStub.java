package org.metadatacenter.cedar.user.resources;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Answers the two Keycloak calls the user summary makes: the admin client's password grant and the
 * read of one user. The user server builds its Keycloak address as {@code https://auth.${CEDAR_HOST}},
 * so the stub serves TLS, and {@link #environment()} points {@code CEDAR_HOST} at {@code localhost} on
 * the stub's port, since every name under {@code .localhost} is the loopback address. The certificate
 * is generated for each run and trusted through the development opt-in the configuration already has.
 *
 * <p>A suite pointed here never reaches a developer's Keycloak, and the summary's success and
 * not-found answers run on every machine rather than depending on what the host can reach.
 */
final class KeycloakStub implements AutoCloseable {

  private static final String REALM = "CEDAR";
  private static final char[] STORE_PASSWORD = "keycloak-stub".toCharArray();

  private final HttpsServer server;
  private final Map<String, String> users = new ConcurrentHashMap<>();
  private final Set<String> usersAskedFor = ConcurrentHashMap.newKeySet();

  private KeycloakStub(HttpsServer server) {
    this.server = server;
  }

  static KeycloakStub start() throws Exception {
    InetAddress loopback = InetAddress.getByName("auth.localhost");
    if (!loopback.isLoopbackAddress()) {
      throw new IllegalStateException("auth.localhost resolves to " + loopback + ", not to the loopback address");
    }
    HttpsServer server = HttpsServer.create(new InetSocketAddress(0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(sslContext()));
    KeycloakStub stub = new KeycloakStub(server);
    server.createContext("/", stub::handle);
    server.start();
    return stub;
  }

  /** The environment entries that send the user server's Keycloak admin client to this stub. */
  Map<String, String> environment() {
    return Map.of(
        "CEDAR_HOST", "localhost:" + server.getAddress().getPort(),
        "CEDAR_KEYCLOAK_ALLOW_INSECURE_TLS", "true");
  }

  /** Makes Keycloak hold an account for the user, created at the given time and linked to one provider. */
  void holdUser(String uuid, long createdTimestamp, String identityProvider, String providerUserId) {
    users.put(uuid, "{\"id\": \"" + uuid + "\", \"username\": \"" + uuid + "\", \"enabled\": true,"
        + " \"createdTimestamp\": " + createdTimestamp + ","
        + " \"federatedIdentities\": [{\"identityProvider\": \"" + identityProvider + "\","
        + " \"userId\": \"" + providerUserId + "\", \"userName\": \"" + providerUserId + "\"}]}");
  }

  /** Whether the user server asked Keycloak for this user. */
  boolean wasAskedFor(String uuid) {
    return usersAskedFor.contains(uuid);
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      exchange.getRequestBody().readAllBytes();
      String path = exchange.getRequestURI().getPath();
      String usersPrefix = "/admin/realms/" + REALM + "/users/";
      if ("POST".equals(exchange.getRequestMethod())
          && path.equals("/realms/" + REALM + "/protocol/openid-connect/token")) {
        respond(exchange, 200, "{\"access_token\": \"stub-access-token\", \"expires_in\": 300,"
            + " \"refresh_expires_in\": 1800, \"refresh_token\": \"stub-refresh-token\", \"token_type\": \"Bearer\"}");
      } else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith(usersPrefix)) {
        String uuid = path.substring(usersPrefix.length());
        usersAskedFor.add(uuid);
        String user = users.get(uuid);
        if (user == null) {
          respond(exchange, 404, "{\"error\": \"User not found\"}");
        } else {
          respond(exchange, 200, user);
        }
      } else {
        respond(exchange, 404, "{\"error\": \"The stub does not answer " + path + "\"}");
      }
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** A key pair and certificate for auth.localhost, made by the JDK's own keytool for this run alone. */
  private static SSLContext sslContext() throws Exception {
    Path directory = Files.createTempDirectory("keycloak-stub");
    Path keystore = directory.resolve("stub.p12");
    try {
      Process keytool = new ProcessBuilder(
          Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
          "-genkeypair", "-alias", "stub", "-keyalg", "EC", "-groupname", "secp256r1",
          "-dname", "CN=auth.localhost", "-ext", "SAN=dns:auth.localhost", "-validity", "2",
          "-storetype", "PKCS12", "-keystore", keystore.toString(),
          "-storepass", new String(STORE_PASSWORD), "-keypass", new String(STORE_PASSWORD))
          .redirectErrorStream(true).start();
      String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!keytool.waitFor(60, TimeUnit.SECONDS) || keytool.exitValue() != 0) {
        throw new IllegalStateException("keytool could not make the stub's certificate: " + output);
      }
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(keystore)) {
        store.load(in, STORE_PASSWORD);
      }
      KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(store, STORE_PASSWORD);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keys.getKeyManagers(), null, null);
      return context;
    } finally {
      Files.deleteIfExists(keystore);
      Files.deleteIfExists(directory);
    }
  }
}
