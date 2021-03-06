/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.soteria.test;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static javax.interceptor.Interceptor.Priority.APPLICATION;
import static javax.security.identitystore.CredentialValidationResult.Status.VALID;
import static javax.security.identitystore.IdentityStore.ValidationType.PROVIDE_GROUPS;
import static javax.security.identitystore.IdentityStore.ValidationType.VALIDATE;
import static org.glassfish.soteria.cdi.CdiUtils.getBeanReferencesByType;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.Priority;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Alternative;
import javax.security.CallerPrincipal;
import javax.security.identitystore.CredentialValidationResult;
import javax.security.identitystore.IdentityStore;
import javax.security.identitystore.IdentityStoreHandler;
import javax.security.identitystore.credential.Credential;

/**
 *
 */
@Alternative
@Priority(APPLICATION)
@ApplicationScoped
public class CustomIdentityStoreHandler implements IdentityStoreHandler {

    private List<IdentityStore> validatingIdentityStores;
    private List<IdentityStore> groupProvidingIdentityStores;

    @PostConstruct
    public void init() {
        List<IdentityStore> identityStores = getBeanReferencesByType(IdentityStore.class, false);

        validatingIdentityStores = identityStores.stream()
                .filter(i -> i.validationTypes().contains(VALIDATE))
                .sorted(comparing(IdentityStore::priority))
                .collect(toList());

        groupProvidingIdentityStores = identityStores.stream()
                .filter(i -> i.validationTypes().contains(PROVIDE_GROUPS))
                .sorted(comparing(IdentityStore::priority))
                .collect(toList());
    }

    @Override
    public CredentialValidationResult validate(Credential credential) {
        CredentialValidationResult  validationResult = null;
        IdentityStore identityStore = null;
        
        // Check stores to validate until one succeeds.
        for (IdentityStore authenticationIdentityStore : validatingIdentityStores) {
            CredentialValidationResult temp = authenticationIdentityStore.validate(credential);
            switch (temp.getStatus()) {

                case NOT_VALIDATED:
                    // Don't do anything
                    break;
                case INVALID:
                    validationResult = temp;
                    break;
                case VALID:
                    validationResult = temp;
                    identityStore = authenticationIdentityStore;
                    break;
                default:
                    throw new IllegalArgumentException("Value not supported "+temp.getStatus());
            }
        }

        if (validationResult.getStatus() != VALID) {
            // No store validated (authenticated), no need to continue
            return validationResult;
        }

        CallerPrincipal callerPrincipal = validationResult.getCallerPrincipal();
        
        List<String> groups = new ArrayList<>();
        if (identityStore.validationTypes().contains(PROVIDE_GROUPS)) {
            groups.addAll(validationResult.getCallerGroups());
        }

        // Ask all stores that were configured for authorization to get the groups for the
        // authenticated caller
        for (IdentityStore authorizationIdentityStore : groupProvidingIdentityStores) {
            groups.addAll(authorizationIdentityStore.getGroupsByCallerPrincipal(callerPrincipal));
        }

        return new CredentialValidationResult(callerPrincipal, groups);

    }
}
