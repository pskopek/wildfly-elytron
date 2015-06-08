/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
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
 */

package org.wildfly.security.auth.login;

import static org.wildfly.security._private.ElytronMessages.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.SSLServerSocketFactory;
import javax.security.sasl.SaslServerFactory;

import org.wildfly.security.auth.spi.AuthenticatedRealmIdentity;
import org.wildfly.security.auth.spi.CredentialSupport;
import org.wildfly.security.auth.spi.RealmIdentity;
import org.wildfly.security.auth.spi.RealmUnavailableException;
import org.wildfly.security.auth.spi.SecurityRealm;
import org.wildfly.security.auth.spi.SupportLevel;
import org.wildfly.security.auth.util.NameRewriter;
import org.wildfly.security.auth.util.RealmMapper;
import org.wildfly.security.authz.RoleMapper;
import org.wildfly.security.sasl.WildFlySasl;
import org.wildfly.security.util._private.UnmodifiableArrayList;

/**
 * A security domain.  Security domains encapsulate a set of security policies.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public final class SecurityDomain {
    private final Map<String, SecurityRealm> realmMap;
    private final String defaultRealmName;
    private final NameRewriter[] preRealmRewriters;
    private final RealmMapper realmMapper;
    private final NameRewriter[] postRealmRewriters;
    private final boolean anonymousAllowed;
    private final ThreadLocal<SecurityIdentity> currentSecurityIdentity = new ThreadLocal<>();
    private final RoleMapper roleMapper;
    private final Map<String, RoleMapper> realmRoleMappers;

    SecurityDomain(final Map<String, SecurityRealm> realmMap, final String defaultRealmName, final NameRewriter[] preRealmRewriters, final RealmMapper realmMapper, final NameRewriter[] postRealmRewriters, RoleMapper roleMapper, Map<String, RoleMapper> realmRoleMappers) {
        assert realmMap.containsKey(defaultRealmName);
        this.realmMap = realmMap;
        this.defaultRealmName = defaultRealmName;
        this.preRealmRewriters = preRealmRewriters;
        this.realmMapper = realmMapper;
        this.postRealmRewriters = postRealmRewriters;
        this.roleMapper = roleMapper == null ? RoleMapper.IDENTITY_ROLE_MAPPER : roleMapper;
        this.realmRoleMappers = realmRoleMappers;
        // todo configurable
        anonymousAllowed = false;
    }

    /**
     * Create a new security domain builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a new authentication context for this security domain which can be used to carry out a single authentication
     * operation.
     *
     * @return the new authentication context
     */
    public ServerAuthenticationContext createNewAuthenticationContext() {
        return new ServerAuthenticationContext(this);
    }

    /**
     * Map the provided name to a {@link RealmIdentity}.
     *
     * @param name the name to map
     * @return the identity for the name
     * @throws RealmUnavailableException if the realm is not able to perform the mapping
     */
    public RealmIdentity mapName(String name) throws RealmUnavailableException {
        for (NameRewriter rewriter : preRealmRewriters) {
            name = rewriter.rewriteName(name);
        }
        String realmName = realmMapper.getRealmMapping(name);
        if (realmName == null) {
            realmName = defaultRealmName;
        }
        SecurityRealm securityRealm = realmMap.get(realmName);
        if (securityRealm == null) {
            securityRealm = realmMap.get(defaultRealmName);
        }
        assert securityRealm != null;
        for (NameRewriter rewriter : postRealmRewriters) {
            name = rewriter.rewriteName(name);
        }
        return securityRealm.createRealmIdentity(name);
    }

    /**
     * Get an SSL server socket factory that authenticates against this security domain.
     *
     * @return the server socket factory
     */
    public SSLServerSocketFactory getSslServerSocketFactory() {
        throw new UnsupportedOperationException();
    }

    /**
     * Get the list of SASL server mechanism names that are provided by the given factory and allowed by this
     * configuration.
     *
     * @param saslServerFactory the SASL server factory
     * @return the list of mechanism names
     */
    public List<String> getSaslServerMechanismNames(SaslServerFactory saslServerFactory) {
        final String[] names = saslServerFactory.getMechanismNames(Collections.singletonMap(WildFlySasl.MECHANISM_QUERY_ALL, "true"));
        // todo: filter down based on SASL selection criteria
        if (names == null || names.length == 0) {
            return Collections.emptyList();
        } else if (names.length == 1) {
            return Collections.singletonList(names[0]);
        } else {
            return new UnmodifiableArrayList<>(names);
        }
    }

    /**
     * Determine whether anonymous authorization is allowed.  Note that this applies only to login authentication
     * protocols and not transport layer security (TLS).
     *
     * @return {@code true} if anonymous logins are allowed, {@code false} if anonymous logins are disallowed
     */
    public boolean isAnonymousAllowed() {
        return anonymousAllowed;
    }

    SecurityRealm getRealm(final String realmName) {
        SecurityRealm securityRealm = realmMap.get(realmName);
        if (securityRealm == null) {
            securityRealm = realmMap.get(defaultRealmName);
        }
        return securityRealm;
    }

    CredentialSupport getCredentialSupport(final Class<?> credentialType) {
        SupportLevel obtainMin, obtainMax, verifyMin, verifyMax;
        obtainMin = obtainMax = verifyMin = verifyMax = null;
        Iterator<SecurityRealm> iterator = realmMap.values().iterator();
        if (iterator.hasNext()) {

            while (iterator.hasNext()) {
                SecurityRealm realm = iterator.next();
                try {
                    final CredentialSupport support = realm.getCredentialSupport(credentialType);

                    final SupportLevel obtainable = support.obtainableSupportLevel();
                    final SupportLevel verification = support.verificationSupportLevel();

                    if (obtainMin == null || obtainMax == null || verifyMin == null || verifyMax == null) {
                        obtainMin = obtainMax = obtainable;
                        verifyMin = verifyMax = verification;
                    } else {
                        if (obtainable.compareTo(obtainMin) < 0) { obtainMin = obtainable; }
                        if (obtainable.compareTo(obtainMax) > 0) { obtainMax = obtainable; }

                        if (verification.compareTo(verifyMin) < 0) { verifyMin = verification; }
                        if (verification.compareTo(verifyMax) > 0) { verifyMax = verification; }
                    }
                } catch (RealmUnavailableException e) {
                }
            }

            if (obtainMin == null || obtainMax == null || verifyMin == null || verifyMax == null) {
                return CredentialSupport.UNSUPPORTED;
            } else {
                return CredentialSupport.getCredentialSupport(minMax(obtainMin, obtainMax), minMax(verifyMin, verifyMax));
            }
        } else {
            return CredentialSupport.UNSUPPORTED;
        }
    }

    private SupportLevel minMax(SupportLevel min, SupportLevel max) {
        if (min == max) return min;
        if (max == SupportLevel.UNSUPPORTED) {
            return SupportLevel.UNSUPPORTED;
        } else if (min == SupportLevel.SUPPORTED) {
            return SupportLevel.SUPPORTED;
        } else {
            return SupportLevel.POSSIBLY_SUPPORTED;
        }
    }

    CredentialSupport getCredentialSupport(final String realmName, final Class<?> credentialType) {
        final SecurityRealm realm = getRealm(realmName);
        try {
            return realm.getCredentialSupport(credentialType);
        } catch (RealmUnavailableException e) {
            return CredentialSupport.UNSUPPORTED;
        }
    }

    SecurityIdentity getCurrentSecurityIdentity() {
        return currentSecurityIdentity.get();
    }

    SecurityIdentity getAndSetCurrentSecurityIdentity(SecurityIdentity newIdentity) {
        try {
            return currentSecurityIdentity.get();
        } finally {
            currentSecurityIdentity.set(newIdentity);
        }
    }

    void setCurrentSecurityIdentity(SecurityIdentity newIdentity) {
        currentSecurityIdentity.set(newIdentity);
    }

    Set<String> mapRolesForCurrentSecurityIdentity() {
        SecurityIdentity securityIdentity = getCurrentSecurityIdentity();

        if (securityIdentity == null) {
            throw new IllegalStateException("No SecurityIdentity set.");
        }

        AuthenticatedRealmIdentity identity = securityIdentity.getAuthenticatedRealmIdentity();
        String realmName = this.defaultRealmName; // TODO: how to obtain the realm name
        RoleMapper roleMapper = this.realmRoleMappers.get(realmName);
        Set<String> mappedRoles = identity.getRoles(); // zeroth role mapping, just grab roles from the identity

        // apply the first level mapping, which is based on the role mapper associated with a realm.
        mappedRoles = roleMapper.mapRoles(mappedRoles);

        // apply the second level mapping, which is based on the role mapper associated with this security domain.
        return this.roleMapper.mapRoles(mappedRoles);
    }

    /**
     * A builder for creating new security domains.
     */
    public static final class Builder {
        private static final NameRewriter[] NONE = new NameRewriter[0];

        private boolean built = false;

        private final ArrayList<NameRewriter> preRealmRewriters = new ArrayList<>();
        private final ArrayList<NameRewriter> postRealmRewriters = new ArrayList<>();
        private final HashMap<String, SecurityRealm> realms = new HashMap<>();
        private String defaultRealmName;
        private RealmMapper realmMapper = RealmMapper.DEFAULT_REALM_MAPPER;
        private RoleMapper roleMapper;
        private final HashMap<String, RoleMapper> realmRoleMappers = new HashMap<>();

        /**
         * Add a pre-realm name rewriter, which rewrites the authentication name before a realm is selected.
         *
         * @param rewriter the name rewriter
         * @return this builder
         */
        public Builder addPreRealmRewriter(NameRewriter rewriter) {
            assertNotBuilt();
            if (rewriter != null) preRealmRewriters.add(rewriter);

            return this;
        }

        /**
         * Add a post-realm name rewriter, which rewrites the authentication name after a realm is selected.
         *
         * @param rewriter the name rewriter
         * @return this builder
         */
        public Builder addPostRealmRewriter(NameRewriter rewriter) {
            assertNotBuilt();
            if (rewriter != null) postRealmRewriters.add(rewriter);

            return this;
        }

        /**
         * Set the realm mapper for this security domain, which selects a realm based on the authentication name.
         *
         * @param realmMapper the realm mapper
         * @return this builder
         */
        public Builder setRealmMapper(RealmMapper realmMapper) {
            assertNotBuilt();
            this.realmMapper = realmMapper == null ? RealmMapper.DEFAULT_REALM_MAPPER : realmMapper;

            return this;
        }

        /**
         * <p>Set the role mapper for this security domain, which will be used to perform the last mapping before
         * returning the roles associated with an identity obtained from this security domain.</p>
         *
         * <p>if not specified, {@link RoleMapper#IDENTITY_ROLE_MAPPER} is used.</p>
         *
         * @param roleMapper the role mapper
         * @return this builder
         */
        public Builder setRoleMapper(RoleMapper roleMapper) {
            assertNotBuilt();
            this.roleMapper = roleMapper;
            return this;
        }

        /**
         * Add a realm to this security domain.
         *
         * @param name the realm's name in this configuration
         * @param realm the realm
         * @return this builder
         */
        public Builder addRealm(String name, SecurityRealm realm) {
            addRealm(name, realm, null);
            return this;
        }

        /**
         * <p>Add a realm to this security domain and associate the given {@link RoleMapper} with it.</p>
         *
         * @param name the realm's name in this configuration
         * @param realm the realm
         * @param roleMapper the role mapper. If null, defaults to {@link RoleMapper#IDENTITY_ROLE_MAPPER}.
         * @return this builder
         */
        public Builder addRealm(String name, SecurityRealm realm, RoleMapper roleMapper) {
            assertNotBuilt();

            if (name == null) {
                throw log.nullParameter("name");
            }

            if (realm == null) {
                throw log.nullParameter("realm");
            }

            this.realms.put(name, realm);
            this.realmRoleMappers.put(name, roleMapper == null ? RoleMapper.IDENTITY_ROLE_MAPPER : roleMapper);

            return this;
        }

        /**
         * Get the default realm name.
         *
         * @return the default realm name
         */
        public String getDefaultRealmName() {
            return defaultRealmName;
        }

        /**
         * Set the default realm name.
         *
         * @param defaultRealmName the default realm name
         */
        public Builder setDefaultRealmName(final String defaultRealmName) {
            assertNotBuilt();
            if (defaultRealmName == null) {
                throw log.nullParameter("defaultRealmName");
            }
            this.defaultRealmName = defaultRealmName;

            return this;
        }

        /**
         * Construct this security domain.
         *
         * @return the new security domain
         */
        public SecurityDomain build() {
            final String defaultRealmName = this.defaultRealmName;
            if (defaultRealmName == null) {
                throw log.nullParameter("defaultRealmName");
            }
            final HashMap<String, SecurityRealm> realmMap = new HashMap<>(realms);
            if (! realmMap.containsKey(defaultRealmName)) {
                throw log.realmMapDoesntContainDefault(defaultRealmName);
            }

            assertNotBuilt();
            built = true;

            NameRewriter[] preRealm = preRealmRewriters.isEmpty() ? NONE : preRealmRewriters.toArray(new NameRewriter[preRealmRewriters.size()]);
            NameRewriter[] postRealm = postRealmRewriters.isEmpty() ? NONE : postRealmRewriters.toArray(new NameRewriter[postRealmRewriters.size()]);
            return new SecurityDomain(realmMap, defaultRealmName, preRealm, realmMapper, postRealm, roleMapper, realmRoleMappers);
        }

        private void assertNotBuilt() {
            if (built) {
                throw log.builderAlreadyBuilt();
            }
        }
    }
}
