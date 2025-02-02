// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.oauth;

import static com.google.gerrit.json.OutputFormat.JSON;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.slf4j.LoggerFactory.getLogger;

import com.google.common.base.CharMatcher;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.auth.oauth.OAuthServiceProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthToken;
import com.google.gerrit.extensions.auth.oauth.OAuthUserInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthVerifier;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import org.scribe.builder.ServiceBuilder;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuthService;
import org.slf4j.Logger;

@Singleton
public class GitLabOAuthService implements OAuthServiceProvider {
  private static final Logger log = getLogger(GitLabOAuthService.class);
  static final String CONFIG_SUFFIX = "-gitlab-oauth";
  private static final String PROTECTED_RESOURCE_URL = "%s/api/v3/user";
  private static final String GITLAB_PROVIDER_PREFIX = "gitlab-oauth:";
  private final OAuthService service;
  private final String rootUrl;

  @Inject
  GitLabOAuthService(
      PluginConfigFactory cfgFactory,
      @PluginName String pluginName,
      @CanonicalWebUrl Provider<String> urlProvider) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName + CONFIG_SUFFIX);
    String canonicalWebUrl = CharMatcher.is('/').trimTrailingFrom(urlProvider.get()) + "/";
    rootUrl = cfg.getString(InitOAuth.ROOT_URL);
    if (!URI.create(rootUrl).isAbsolute()) {
      throw new ProvisionException("Root URL must be absolute URL");
    }
    service =
        new ServiceBuilder()
            .provider(new GitLabApi(rootUrl))
            .apiKey(cfg.getString(InitOAuth.CLIENT_ID))
            .apiSecret(cfg.getString(InitOAuth.CLIENT_SECRET))
            .callback(canonicalWebUrl + "oauth")
            .build();
  }

  @Override
  public OAuthUserInfo getUserInfo(OAuthToken token) throws IOException {
    final String protectedResourceUrl = String.format(PROTECTED_RESOURCE_URL, rootUrl);
    OAuthRequest request = new OAuthRequest(Verb.GET, protectedResourceUrl);
    Token t = new Token(token.getToken(), token.getSecret(), token.getRaw());
    service.signRequest(t, request);

    Response response = request.send();
    if (response.getCode() != SC_OK) {
      throw new IOException(
          String.format(
              "Status %s (%s) for request %s",
              response.getCode(), response.getBody(), request.getUrl()));
    }
    JsonElement userJson = JSON.newGson().fromJson(response.getBody(), JsonElement.class);
    if (log.isDebugEnabled()) {
      log.debug("User info response: {}", response.getBody());
    }
    JsonObject jsonObject = userJson.getAsJsonObject();
    if (jsonObject == null || jsonObject.isJsonNull()) {
      throw new IOException("Response doesn't contain 'user' field" + jsonObject);
    }
    JsonElement id = jsonObject.get("id");
    JsonElement username = jsonObject.get("username");
    JsonElement email = jsonObject.get("email");
    JsonElement name = jsonObject.get("name");
    return new OAuthUserInfo(
        GITLAB_PROVIDER_PREFIX + id.getAsString(),
        username == null || username.isJsonNull() ? null : username.getAsString(),
        email == null || email.isJsonNull() ? null : email.getAsString(),
        name == null || name.isJsonNull() ? null : name.getAsString(),
        null);
  }

  @Override
  public OAuthToken getAccessToken(OAuthVerifier rv) {
    Verifier vi = new Verifier(rv.getValue());
    Token to = service.getAccessToken(null, vi);
    return new OAuthToken(to.getToken(), to.getSecret(), to.getRawResponse());
  }

  @Override
  public String getAuthorizationUrl() {
    return service.getAuthorizationUrl(null);
  }

  @Override
  public String getVersion() {
    return service.getVersion();
  }

  @Override
  public String getName() {
    return "GitLab OAuth2";
  }
}
