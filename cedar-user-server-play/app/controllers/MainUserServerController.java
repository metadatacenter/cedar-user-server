package controllers;

import org.metadatacenter.server.play.AbstractCedarController;
import play.mvc.Result;

public class MainUserServerController extends AbstractUserServerController {

  public static Result index() {
    return ok("CEDAR User Server.");
  }

  /* For CORS */
  public static Result preflight(String all) {
    return AbstractCedarController.preflight(all);
  }

}
