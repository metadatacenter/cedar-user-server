package controllers;

import org.metadatacenter.server.play.AbstractCedarController;
import play.Configuration;
import play.Play;

public abstract class AbstractUserServerController extends AbstractCedarController {

  protected static Configuration config;

  static {
    config = Play.application().configuration();
  }

}
