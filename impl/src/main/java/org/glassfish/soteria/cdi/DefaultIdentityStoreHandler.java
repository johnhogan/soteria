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
package org.glassfish.soteria.cdi;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;
import static javax.security.identitystore.CredentialValidationResult.INVALID_RESULT;
import static javax.security.identitystore.CredentialValidationResult.Status.VALID;
import static javax.security.identitystore.IdentityStore.ValidationType.PROVIDE_GROUPS;
import static javax.security.identitystore.IdentityStore.ValidationType.VALIDATE;
import static org.glassfish.soteria.cdi.CdiUtils.getBeanReferencesByType;

import java.util.ArrayList;
import java.util.List;

import javax.security.CallerPrincipal;
import javax.security.identitystore.CredentialValidationResult;
import javax.security.identitystore.IdentityStore;
import javax.security.identitystore.IdentityStoreHandler;
import javax.security.identitystore.credential.Credential;

/**
 *
 */
public class DefaultIdentityStoreHandler implements IdentityStoreHandler {

    private List<IdentityStore> authenticationIdentityStores;
    private List<IdentityStore> authorizationIdentityStores;

    public void init() {
    	List<IdentityStore> identityStores = getBeanReferencesByType(IdentityStore.class, false);

    	authenticationIdentityStores = identityStores.stream()
    												 .filter(i -> i.validationTypes().contains(VALIDATE))
    												 .sorted(comparing(IdentityStore::priority))
    												 .collect(toList());

    	authorizationIdentityStores = identityStores.stream()
				 									.filter(i -> i.validationTypes().contains(PROVIDE_GROUPS) && !i.validationTypes().contains(VALIDATE))
		 											.sorted(comparing(IdentityStore::priority))
	 												.collect(toList());
    }

    @Override
    public CredentialValidationResult validate(Credential credential) {

        CredentialValidationResult validationResult = null;
        IdentityStore identityStore = null;

        // Check stores to authenticate until one succeeds.
        for (IdentityStore authenticationIdentityStore : authenticationIdentityStores) {
            validationResult = authenticationIdentityStore.validate(credential);
            if (validationResult.getStatus() == VALID) {
                identityStore = authenticationIdentityStore;
                break;
            }
        }

        if (validationResult == null) {
            // No authentication store at all
            return INVALID_RESULT;
        }

        if (validationResult.getStatus() != VALID) {
            // No store authenticated, no need to continue
            return validationResult;
        }

        CallerPrincipal callerPrincipal = validationResult.getCallerPrincipal();
        List<String> groups = new ArrayList<>();

        // Take the groups from the identity store that validated the credentials only
        // if it has been set to provide groups.
        if (identityStore.validationTypes().contains(PROVIDE_GROUPS)) {
            groups.addAll(validationResult.getCallerGroups());
        }

        // Ask all stores that were configured for group providing only to get the groups for the
        // authenticated caller
        for (IdentityStore authorizationIdentityStore : authorizationIdentityStores) {
            groups.addAll(authorizationIdentityStore.getGroupsByCallerPrincipal(callerPrincipal));
        }

        return new CredentialValidationResult(callerPrincipal, groups);
    }

}
