package org.metadatacenter.cedar.user;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.metadatacenter.bridge.CedarDataServices;
import org.metadatacenter.cedar.user.health.UserServerHealthCheck;
import org.metadatacenter.cedar.user.resources.IndexResource;
import org.metadatacenter.cedar.user.resources.UsersResource;
import org.metadatacenter.cedar.util.dw.CedarDropwizardApplicationUtil;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.service.mongodb.UserServiceMongoDB;

public class UserServerApplication extends Application<UserServerConfiguration> {

  protected static CedarConfig cedarConfig;
  protected static UserService userService;

  public static void main(String[] args) throws Exception {
    new UserServerApplication().run(args);
  }

  @Override
  public String getName() {
    return "user-server";
  }

  @Override
  public void initialize(Bootstrap<UserServerConfiguration> bootstrap) {
    cedarConfig = CedarConfig.getInstance();
    CedarDataServices.getInstance(cedarConfig);

    CedarDropwizardApplicationUtil.setupKeycloak();

    userService = new UserServiceMongoDB(
        cedarConfig.getMongoConfig().getDatabaseName(),
        cedarConfig.getMongoCollectionName(CedarNodeType.USER));

    UsersResource.injectUserService(userService);
  }

  @Override
  public void run(UserServerConfiguration configuration, Environment environment) {
    final IndexResource index = new IndexResource();
    environment.jersey().register(index);

    final UsersResource users = new UsersResource(cedarConfig);
    environment.jersey().register(users);

    final UserServerHealthCheck healthCheck = new UserServerHealthCheck();
    environment.healthChecks().register("message", healthCheck);

    CedarDropwizardApplicationUtil.setupEnvironment(environment);
  }
}
