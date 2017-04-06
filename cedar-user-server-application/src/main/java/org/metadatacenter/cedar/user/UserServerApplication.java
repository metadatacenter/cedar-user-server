package org.metadatacenter.cedar.user;

import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import org.metadatacenter.cedar.user.health.UserServerHealthCheck;
import org.metadatacenter.cedar.user.resources.IndexResource;
import org.metadatacenter.cedar.user.resources.UsersResource;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceApplication;
import org.metadatacenter.model.ServerName;

public class UserServerApplication extends CedarMicroserviceApplication<UserServerConfiguration> {

  public static void main(String[] args) throws Exception {
    new UserServerApplication().run(args);
  }

  @Override
  protected ServerName getServerName() {
    return ServerName.USER;
  }

  @Override
  public void initializeApp(Bootstrap<UserServerConfiguration> bootstrap) {
    UsersResource.injectUserService(userService);
  }

  @Override
  public void runApp(UserServerConfiguration configuration, Environment environment) {
    final IndexResource index = new IndexResource();
    environment.jersey().register(index);

    final UsersResource users = new UsersResource(cedarConfig);
    environment.jersey().register(users);

    final UserServerHealthCheck healthCheck = new UserServerHealthCheck();
    environment.healthChecks().register("message", healthCheck);
  }

}
