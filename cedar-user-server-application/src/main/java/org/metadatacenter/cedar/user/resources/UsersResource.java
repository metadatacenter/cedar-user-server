package org.metadatacenter.cedar.user.resources;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.databind.JsonNode;
import org.metadatacenter.config.CedarConfig;
import org.metadatacenter.error.CedarErrorKey;
import org.metadatacenter.exception.CedarException;
import org.metadatacenter.exception.CedarProcessingException;
import org.metadatacenter.rest.context.CedarRequestContext;
import org.metadatacenter.rest.context.CedarRequestContextFactory;
import org.metadatacenter.server.result.BackendCallResult;
import org.metadatacenter.server.security.model.user.CedarUser;
import org.metadatacenter.server.service.UserService;
import org.metadatacenter.util.CedarUserNameUtil;
import org.metadatacenter.util.http.CedarResponse;
import org.metadatacenter.util.json.JsonMapper;
import org.metadatacenter.util.mongo.MongoUtils;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

import static org.metadatacenter.constant.CedarPathParameters.PP_ID;
import static org.metadatacenter.rest.assertion.GenericAssertions.LoggedIn;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
public class UsersResource extends AbstractUserServerResource {

  private static UserService userService;

  public UsersResource(CedarConfig cedarConfig) {
    super(cedarConfig);
  }

  public static void injectUserService(UserService us) {
    userService = us;
  }

  @GET
  @Timed
  @Path("/{id}")
  public Response findOwnUser(@PathParam(PP_ID) String uuid) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);

    c.must(c.user()).be(LoggedIn);

    CedarUser currentUser = c.getCedarUser();

    String id = linkedDataUtil.getUserId(uuid);

    if (!id.equals(currentUser.getId())) {
      return CedarResponse.forbidden()
          .id(id)
          .errorKey(CedarErrorKey.READ_OTHER_PROFILE_FORBIDDEN)
          .errorMessage("You are not allowed to read other user's profile!")
          .parameter("currentUserId", currentUser.getId())
          .build();
    }

    JsonNode user = JsonMapper.MAPPER.valueToTree(currentUser);
    // Remove autogenerated _id field to avoid exposing it
    MongoUtils.removeIdField(user);

    return Response.ok().entity(user).build();
  }

  @GET
  @Timed
  @Path("/{id}/summary")
  public Response findUserSummary(@PathParam(PP_ID) String uuid) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);

    c.must(c.user()).be(LoggedIn);

    String id = linkedDataUtil.getUserId(uuid);

    CedarUser lookupUser = null;
    try {
      lookupUser = userService.findUser(id);
    } catch (Exception e) {
      throw new CedarProcessingException(e);
    }

    Map<String, String> summary = new HashMap<>();
    summary.put("userId", id);
    summary.put("screenName", CedarUserNameUtil.getDisplayName(cedarConfig, lookupUser));
    return Response.ok().entity(summary).build();
  }

  @PUT
  @Timed
  @Path("/{id}")
  public Response updateUser(@PathParam(PP_ID) String uuid) throws CedarException {
    CedarRequestContext c = CedarRequestContextFactory.fromRequest(request);

    c.must(c.user()).be(LoggedIn);

    CedarUser currentUser = c.getCedarUser();

    String id = linkedDataUtil.getUserId(uuid);

    if (!id.equals(currentUser.getId())) {
      return CedarResponse.forbidden()
          .id(id)
          .errorKey(CedarErrorKey.UPDATE_OTHER_PROFILE_FORBIDDEN)
          .errorMessage("You are not allowed to update other user's profile!")
          .parameter("currentUserId", currentUser.getId())
          .build();
    }

    JsonNode modifications = c.request().getRequestBody().asJson();

    BackendCallResult<CedarUser> cedarUserBackendCallResult = userService.patchUser(id, modifications);
    if (cedarUserBackendCallResult.isError()) {
      return CedarResponse.from(cedarUserBackendCallResult);
    } else {
      CedarUser updatedUser = cedarUserBackendCallResult.getPayload();

      JsonNode updatedUserNode = JsonMapper.MAPPER.valueToTree(updatedUser);
      // Remove autogenerated _id field to avoid exposing it
      MongoUtils.removeIdField(updatedUserNode);

      return Response.ok().entity(updatedUserNode).build();
    }
  }
}
