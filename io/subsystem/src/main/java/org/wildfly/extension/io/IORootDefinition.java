/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.wildfly.extension.io;

import org.jboss.as.controller.ReloadRequiredRemoveStepHandler;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.wildfly.io.IOServiceDescriptor;

/**
 * @author <a href="mailto:tomaz.cerar@redhat.com">Tomaz Cerar</a> (c) 2013 Red Hat Inc.
 */
class IORootDefinition extends SimpleResourceDefinition {

    static final RuntimeCapability<Void> IO_MAX_THREADS_RUNTIME_CAPABILITY = RuntimeCapability.Builder.of(IOServiceDescriptor.MAX_THREADS).build();

    IORootDefinition() {
        super(new SimpleResourceDefinition.Parameters(IOExtension.SUBSYSTEM_PATH, IOExtension.getResolver())
                .setAddHandler(new IOSubsystemAdd())
                .setAddRestartLevel(OperationEntry.Flag.RESTART_NONE)
                .setRemoveHandler(ReloadRequiredRemoveStepHandler.INSTANCE)
                .setRemoveRestartLevel(OperationEntry.Flag.RESTART_ALL_SERVICES)
                .addCapabilities(IO_MAX_THREADS_RUNTIME_CAPABILITY));
    }

    @Override
    public void registerChildren(ManagementResourceRegistration registration) {
        registration.registerSubModel(new WorkerResourceDefinition());
        registration.registerSubModel(new BufferPoolResourceDefinition());
    }
}
