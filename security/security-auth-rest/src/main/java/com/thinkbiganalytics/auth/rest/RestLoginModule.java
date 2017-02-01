package com.thinkbiganalytics.auth.rest;

/*-
 * #%L
 * REST API Authentication
 * %%
 * Copyright (C) 2017 ThinkBig Analytics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.thinkbiganalytics.auth.jaas.AbstractLoginModule;
import com.thinkbiganalytics.rest.JerseyRestClient;
import com.thinkbiganalytics.security.rest.model.UserPrincipal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.AccountNotFoundException;
import javax.security.auth.login.CredentialException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.spi.LoginModule;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.WebApplicationException;

/**
 * Authenticates users by querying an external REST API. This allows the UI module to get a user's groups from the Services module.
 */
public class RestLoginModule extends AbstractLoginModule implements LoginModule {

    private static final Logger log = LoggerFactory.getLogger(RestLoginModule.class);

    /** Option for the URL of the REST API endpoint */
    public static final String LOGIN_URL = "loginUrl";

    /** REST API client configuration */
    private LoginJerseyClientConfig config;

    @Override
    public void initialize(@Nonnull final Subject subject, @Nonnull final CallbackHandler callbackHandler, @Nonnull final Map<String, ?> sharedState, @Nonnull final Map<String, ?> options) {
        super.initialize(subject, callbackHandler, sharedState, options);

        final URI uri = URI.create(options.get(LOGIN_URL).toString());
        config = new LoginJerseyClientConfig(uri);
    }

    @Override
    protected boolean doLogin() throws Exception {
        // Get username and password
        final NameCallback nameCallback = new NameCallback("Username: ");
        final PasswordCallback passwordCallback = new PasswordCallback("Password: ", false);
        handle(nameCallback, passwordCallback);

        // Fetch user from API
        final String username = nameCallback.getName();
        final String password = new String(passwordCallback.getPassword());

        final LoginJerseyClientConfig userConfig = new LoginJerseyClientConfig(config);
        userConfig.setUsername(username);
        userConfig.setPassword(password);

        final UserPrincipal user;
        try {
            user = getClient(userConfig).get("/v1/about/me", null, UserPrincipal.class);
        } catch (final NotAuthorizedException e) {
            log.debug("Received unauthorized response from Login API for user: {}", username);
            throw new CredentialException("The username and password combination do not match.");
        } catch (final ProcessingException e) {
            log.error("Failed to process response from Login API for user: {}", username, e);
            throw new FailedLoginException("The login service is unavailable.");
        } catch (final WebApplicationException e) {
            log.error("Received unexpected response from Login API for user: {}", username, e);
            throw new FailedLoginException("The login service is unavailable.");
        }

        // Parse response
        if (user == null) {
            log.debug("Received null response from Login API for user: {}", username);
            throw new AccountNotFoundException("No account exists with the name: " + username);
        } else if (!user.isEnabled()) {
            log.debug("User from Login API is disabled: {}", username);
            throw new AccountLockedException("The account \"" + username + "\" is currently disabled");
        }

        addNewUserPrincipal(user.getSystemName());
        user.getGroups().forEach(this::addNewGroupPrincipal);
        return true;
    }

    @Override
    protected boolean doCommit() throws Exception {
        getSubject().getPrincipals().addAll(getAllPrincipals());
        return true;
    }

    @Override
    protected boolean doAbort() throws Exception {
        return doLogout();
    }

    @Override
    protected boolean doLogout() throws Exception {
        getSubject().getPrincipals().removeAll(getAllPrincipals());
        return true;
    }

    /**
     * Gets a Jersey client using the specified configuration.
     *
     * @param config the login configuration
     * @return the Jersey client
     */
    @Nonnull
    JerseyRestClient getClient(@Nonnull final LoginJerseyClientConfig config) {
        return new JerseyRestClient(config);
    }
}
