package org.metadatacenter.cedar.user.config;

import org.metadatacenter.model.SystemComponent;
import org.metadatacenter.util.test.AbstractCedarConfigTest;

public class CedarConfigUserTest extends AbstractCedarConfigTest {

  @Override
  protected SystemComponent getSystemComponent() {
    return SystemComponent.SERVER_USER;
  }

}
