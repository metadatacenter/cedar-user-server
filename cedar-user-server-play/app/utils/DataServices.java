package utils;

import controllers.UserServerController;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.model.CedarNodeType;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.server.service.mongodb.UserServiceMongoDB;

public class DataServices {

  private static DataServices instance = new DataServices();
  private static UserService userService;
  private static CedarConfig cedarConfig;

  public static DataServices getInstance() {
    return instance;
  }

  private DataServices() {
    cedarConfig = CedarConfig.getInstance();
    userService = new UserServiceMongoDB(cedarConfig.getMongoConfig().getDatabaseName(),
        cedarConfig.getMongoCollectionName(CedarNodeType.USER));
    UserServerController.injectUserService(userService);
  }

  public UserService getUserService() {
    return userService;
  }

  public static CedarConfig getCedarConfig() {
    return cedarConfig;
  }
}
