package org.metadatacenter.cedar.user.resources;

import org.metadatacenter.cedar.util.dw.CedarMicroserviceResource;
import org.metadatacenter.config.CedarConfig;

public class AbstractUserServerResource extends CedarMicroserviceResource {

  protected AbstractUserServerResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

}
