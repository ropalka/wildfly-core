/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
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

package org.jboss.as.controller.access;

import static org.jboss.as.controller.security.ControllerPermission.GET_IN_VM_CALL_STATE;
import static org.jboss.as.controller.security.ControllerPermission.PERFORM_IN_VM_CALL;

import java.security.Permission;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

/**
 * Utility class for executing in-vm calls.
 *
 * @author <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
public final class InVmAccess {

    private static final ThreadLocal<Boolean> IN_VM_CALL = new ThreadLocal<Boolean>() {

        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }

    };

    private InVmAccess() {
    }

    /**
     * Run an action as an in-vm action.
     *
     * @param action the action to run
     * @param <T> the action return type
     * @return the action result (may be {@code null})
     */
    public static <T> T runInVm(PrivilegedAction<T> action) {
        checkPermission(PERFORM_IN_VM_CALL);

        if (action == null) return null;
        Boolean originalValue = IN_VM_CALL.get();

        try {
            IN_VM_CALL.set(Boolean.TRUE);
            return action.run();
        } finally {
            IN_VM_CALL.set(originalValue);
        }
    }

    /**
     * Run an action as an in-vm action.
     *
     * @param action the action to run
     * @param <T> the action return type
     * @return the action result (may be {@code null})
     * @throws PrivilegedActionException if the action fails
     */
    public static <T> T runInVm(PrivilegedExceptionAction<T> action) throws PrivilegedActionException {
        checkPermission(PERFORM_IN_VM_CALL);

        if (action == null) return null;
        Boolean originalValue = IN_VM_CALL.get();

        try {
            IN_VM_CALL.set(Boolean.TRUE);
            return action.run();
        } catch (RuntimeException | PrivilegedActionException e) {
            throw e;
        } catch (Exception e) {
            throw new PrivilegedActionException(e);
        } finally {
            IN_VM_CALL.set(originalValue);
        }
    }

    /**
     * Is the current call an in-vm call?
     *
     * @return {@code true} if the current call is an in-vm call, {@code false} otherwise.
     */
    public static boolean isInVmCall() {
        checkPermission(GET_IN_VM_CALL_STATE);

        return IN_VM_CALL.get();
    }

    private static void checkPermission(final Permission permission) {
        SecurityManager securityManager = System.getSecurityManager();
        if (securityManager != null) {
            securityManager.checkPermission(permission);
        }
    }

}
