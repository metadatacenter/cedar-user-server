package org.metadatacenter.cedar.user.health;

import com.codahale.metrics.health.HealthCheck;

public class UserServerHealthCheck extends HealthCheck {

  public UserServerHealthCheck() {
  }

  @Override
  protected Result check() throws Exception {
    if (2 * 2 == 5) {
      return Result.unhealthy("Unhealthy, because 2 * 2 == 5");
    }
    return Result.healthy();
  }
}