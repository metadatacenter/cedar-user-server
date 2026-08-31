package org.metadatacenter.cedar.user;

import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import org.metadatacenter.cedar.user.resources.UsersResource;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceIndexResource;
import org.metadatacenter.cedar.util.dw.CedarDefaultHealthCheck;
import org.metadatacenter.cedar.util.dw.CedarMicroserviceApplication;
import org.metadatacenter.config.CedarConfig;
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
  protected void initializeWithBootstrap(Bootstrap<UserServerConfiguration> bootstrap, CedarConfig cedarConfig) {
  }

  @Override
  public void initializeApp() {
    UsersResource.injectUserService(userService);
  }

  @Override
  public void runApp(UserServerConfiguration configuration, Environment environment) {
    final CedarMicroserviceIndexResource index =
        new CedarMicroserviceIndexResource(cedarConfig, getServerName());
    environment.jersey().register(index);

    final UsersResource users = new UsersResource(cedarConfig);
    environment.jersey().register(users);

    final CedarDefaultHealthCheck healthCheck = new CedarDefaultHealthCheck();
    environment.healthChecks().register("message", healthCheck);
  }

}
