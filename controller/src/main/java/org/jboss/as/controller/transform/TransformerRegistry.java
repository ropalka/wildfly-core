/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.controller.transform;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.jboss.as.controller.ModelVersion;
import org.jboss.as.controller.ModelVersionRange;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.registry.GlobalTransformerRegistry;
import org.jboss.as.controller.registry.OperationTransformerRegistry;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleLoadException;

/**
 * Global transformers registry.
 *
 * @author <a href="mailto:tomaz.cerar@redhat.com">Tomaz Cerar</a>
 * @author Emanuel Muckenhuber
 */
public final class TransformerRegistry {

    public static final ModelNode DISCARD_OPERATION = new ModelNode();
    static {
        DISCARD_OPERATION.get(OP).set("discard");
        DISCARD_OPERATION.get(OP_ADDR).setEmptyList();
        DISCARD_OPERATION.protect();
    }

    private static final PathElement HOST = PathElement.pathElement(ModelDescriptionConstants.HOST);
    private static final PathElement PROFILE = PathElement.pathElement(ModelDescriptionConstants.PROFILE);
    private static final PathElement SERVER = PathElement.pathElement(ModelDescriptionConstants.RUNNING_SERVER);

    private final GlobalTransformerRegistry domain = new GlobalTransformerRegistry();
    private final GlobalTransformerRegistry subsystem = new GlobalTransformerRegistry();

    TransformerRegistry() {
        // Initialize the empty paths
        domain.createChildRegistry(PathAddress.pathAddress(PROFILE), ModelVersion.create(0), ResourceTransformer.DEFAULT, false);
        domain.createChildRegistry(PathAddress.pathAddress(HOST), ModelVersion.create(0), ResourceTransformer.DEFAULT, false);
        domain.createChildRegistry(PathAddress.pathAddress(HOST, SERVER), ModelVersion.create(0), ResourceTransformer.DEFAULT, false);
    }

    public void loadAndRegisterTransformers(String name, ModelVersion subsystemVersion, String extensionModuleName) {
        try {
            SubsystemTransformerRegistration transformerRegistration = new SubsystemTransformerRegistrationImpl(name, subsystemVersion);
            if (Module.getCallerModule() != null) { //only register when running in modular environment, testsuite does its own loading
                for (ExtensionTransformerRegistration registration : Module.loadServiceFromCallerModuleLoader(extensionModuleName, ExtensionTransformerRegistration.class)) {
                    if (registration.getSubsystemName().equals(name)) { //to prevent registering transformers for different subsystems
                        registration.registerTransformers(transformerRegistration);
                    }
                }
            }
        } catch (ModuleLoadException e) {
            throw ControllerLogger.ROOT_LOGGER.couldNotLoadModuleForTransformers(extensionModuleName, e);
        }
    }

    public SubsystemTransformerRegistration createSubsystemTransformerRegistration(String name, ModelVersion currentVersion){
        return new SubsystemTransformerRegistrationImpl(name, currentVersion);
    }

    private class SubsystemTransformerRegistrationImpl implements SubsystemTransformerRegistration{
        private final String name;
        private final ModelVersion currentVersion;


        public SubsystemTransformerRegistrationImpl(String name, ModelVersion currentVersion) {
            this.name = name;
            this.currentVersion = currentVersion;
        }

        @Override
        public TransformersSubRegistration registerModelTransformers(final ModelVersionRange range, final ResourceTransformer subsystemTransformer) {
            return registerSubsystemTransformers(name, range, subsystemTransformer);
        }

        @Override
        public TransformersSubRegistration registerModelTransformers(ModelVersionRange version, ResourceTransformer resourceTransformer, OperationTransformer operationTransformer, boolean placeholder) {
            return registerSubsystemTransformers(name, version, resourceTransformer, operationTransformer, placeholder);
        }

        @Override
        public TransformersSubRegistration registerModelTransformers(ModelVersionRange version, CombinedTransformer combinedTransformer) {
            return registerSubsystemTransformers(name, version, combinedTransformer);
        }

        @Override
        public ModelVersion getCurrentSubsystemVersion() {
            return currentVersion;
        }
    }

    /**
     * Register a subsystem transformer.
     *
     * @param name the subsystem name
     * @param range the version range
     * @param subsystemTransformer the resource transformer
     * @return the sub registry
     */
    public TransformersSubRegistration registerSubsystemTransformers(final String name, final ModelVersionRange range, final ResourceTransformer subsystemTransformer) {
        return  registerSubsystemTransformers(name, range, subsystemTransformer, OperationTransformer.DEFAULT, false);
    }

    /**
     * Register a subsystem transformer.
     *
     * @param name the subsystem name
     * @param range the version range
     * @param subsystemTransformer the resource transformer
     * @param operationTransformer the operation transformer
     * @param placeholder whether or not the registered transformers are placeholders
     * @return the sub registry
     */
    public TransformersSubRegistration registerSubsystemTransformers(final String name, final ModelVersionRange range, final ResourceTransformer subsystemTransformer, final OperationTransformer operationTransformer, boolean placeholder) {
        final PathAddress subsystemAddress = PathAddress.EMPTY_ADDRESS.append(PathElement.pathElement(SUBSYSTEM, name));
        for(final ModelVersion version : range.getVersions()) {
            subsystem.createChildRegistry(subsystemAddress, version, subsystemTransformer, operationTransformer, placeholder);
        }
        return new TransformersSubRegistrationImpl(range, subsystem, subsystemAddress);
    }

    /**
     * Get the sub registry for the domain.
     *
     * @param range the version range
     * @return the sub registry
     */
    public TransformersSubRegistration getDomainRegistration(final ModelVersionRange range) {
        final PathAddress address = PathAddress.EMPTY_ADDRESS;
        return new TransformersSubRegistrationImpl(range, domain, address);
    }

    /**
     * Get the sub registry for the hosts.
     *
     * @param range the version range
     * @return the sub registry
     */
    public TransformersSubRegistration getHostRegistration(final ModelVersionRange range) {
        final PathAddress address = PathAddress.EMPTY_ADDRESS.append(HOST);
        return new TransformersSubRegistrationImpl(range, domain, address);
    }

    /**
     * Get the sub registry for the servers.
     *
     * @param range the version range
     * @return the sub registry
     */
    public TransformersSubRegistration getServerRegistration(final ModelVersionRange range) {
        final PathAddress address = PathAddress.EMPTY_ADDRESS.append(HOST, SERVER);
        return new TransformersSubRegistrationImpl(range, domain, address);
    }

    /**
     * Resolve the host registry.
     *
     * @param mgmtVersion the mgmt version
     * @param subsystems the subsystems
     * @return the transformer registry
     */
    public OperationTransformerRegistry resolveHost(final ModelVersion mgmtVersion, final ModelNode subsystems) {
        return resolveHost(mgmtVersion, resolveVersions(subsystems));

    }

    /**
     * Resolve the host registry.
     *
     * @param mgmtVersion the mgmt version
     * @param subsystems the subsystems
     * @return the transformer registry
     */
    public OperationTransformerRegistry resolveHost(final ModelVersion mgmtVersion, final Map<PathAddress, ModelVersion> subsystems) {
        // The domain / host / servers
        final OperationTransformerRegistry root = domain.create(mgmtVersion, Collections.<PathAddress, ModelVersion>emptyMap());
        subsystem.mergeSubtree(root, PathAddress.pathAddress(PROFILE), subsystems);
        subsystem.mergeSubtree(root, PathAddress.pathAddress(HOST, SERVER), subsystems);
        return root;
    }

    /**
     * Resolve the server registry.
     *
     * @param mgmtVersion the mgmt version
     * @param subsystems the subsystems
     * @return the transformer registry
     */
    public OperationTransformerRegistry resolveServer(final ModelVersion mgmtVersion, final ModelNode subsystems) {
        return resolveServer(mgmtVersion, resolveVersions(subsystems));
    }

    /**
     * Resolve the server registry.
     *
     * @param mgmtVersion the mgmt version
     * @param subsystems the subsystems
     * @return the transformer registry
     */
    public OperationTransformerRegistry resolveServer(final ModelVersion mgmtVersion, final Map<PathAddress, ModelVersion> subsystems) {
        // this might not be all that useful after all, since the operation to remote servers go through the host proxies anyway
        final OperationTransformerRegistry root = domain.create(mgmtVersion, Collections.<PathAddress, ModelVersion>emptyMap());
        return subsystem.mergeSubtree(root, PathAddress.pathAddress(HOST, SERVER), subsystems);
    }

    /**
     * Add a new subsystem to a given registry.
     *
     * @param registry the registry
     * @param name the subsystem name
     * @param version the version
     */
    void addSubsystem(final OperationTransformerRegistry registry, final String name, final ModelVersion version) {
        final OperationTransformerRegistry profile = registry.getChild(PathAddress.pathAddress(PROFILE));
        final OperationTransformerRegistry server = registry.getChild(PathAddress.pathAddress(HOST, SERVER));
        final PathAddress address = PathAddress.pathAddress(PathElement.pathElement(ModelDescriptionConstants.SUBSYSTEM, name));
        subsystem.mergeSubtree(profile, Collections.singletonMap(address, version));
        if(server != null) {
            subsystem.mergeSubtree(server, Collections.singletonMap(address, version));
        }
    }

    public static Map<PathAddress, ModelVersion> resolveVersions(ExtensionRegistry extensionRegistry) {

        final ModelNode subsystems = new ModelNode();
        for (final String extension : extensionRegistry.getExtensionModuleNames()) {
            extensionRegistry.recordSubsystemVersions(extension, subsystems);
        }
        return resolveVersions(subsystems);
    }

    public static Map<PathAddress, ModelVersion> resolveVersions(final ModelNode subsystems) {
        final PathAddress base = PathAddress.EMPTY_ADDRESS;
        final Map<PathAddress, ModelVersion> versions = new HashMap<PathAddress, ModelVersion>();
        for(final Property property : subsystems.asPropertyList()) {
            final String name = property.getName();
            final PathAddress address = base.append(PathElement.pathElement(ModelDescriptionConstants.SUBSYSTEM, name));
            versions.put(address, ModelVersion.fromString(property.getValue().asString()));
        }
        return versions;
    }

    static ModelVersion convert(final String version) {
        final String[] s = version.split("\\.");
        final int length = s.length;
        if(length > 3) {
            throw new IllegalStateException();
        }
        int major = Integer.parseInt(s[0]);
        int minor = length > 1 ? Integer.parseInt(s[1]) : 0;
        int micro = length == 3 ? Integer.parseInt(s[2]) : 0;
        return ModelVersion.create(major, minor, micro);
    }

    public static class Factory {

        /**
         * Create a new Transformer registry.
         *
         * @return the created transformer registry
         */
        public static TransformerRegistry create() {
            return new TransformerRegistry();
        }

    }

    public static class TransformersSubRegistrationImpl implements TransformersSubRegistration {

        private final PathAddress current;
        private final ModelVersionRange range;
        private final GlobalTransformerRegistry registry;

        public TransformersSubRegistrationImpl(ModelVersionRange range, GlobalTransformerRegistry registry, PathAddress parent) {
            this.range = range;
            this.registry = registry;
            this.current = parent;
        }

        @Override
        public TransformersSubRegistration registerSubResource(PathElement element) {
            return registerSubResource(element, ResourceTransformer.DEFAULT, OperationTransformer.DEFAULT);
        }

        @Override
        public TransformersSubRegistration registerSubResource(PathElement element, boolean discard) {
            if(discard) {
                final PathAddress address = current.append(element);
                for(final ModelVersion version : range.getVersions()) {
                    registry.createDiscardingChildRegistry(address, version);
                }
                return new TransformersSubRegistrationImpl(range, registry, address);
            }
            return registerSubResource(element, ResourceTransformer.DEFAULT, OperationTransformer.DEFAULT);
        }

        @Override
        public TransformersSubRegistration registerSubResource(PathElement element, OperationTransformer operationTransformer) {
            return registerSubResource(element, ResourceTransformer.DEFAULT, operationTransformer);
        }

        @Override
        public TransformersSubRegistration registerSubResource(PathElement element, ResourceTransformer resourceTransformer) {
            return registerSubResource(element, resourceTransformer, OperationTransformer.DEFAULT);
        }

        @Override
        public TransformersSubRegistration registerSubResource(PathElement element, CombinedTransformer transformer) {
            return registerSubResource(element, transformer, transformer);
        }

        @Override
        public TransformersSubRegistration registerSubResource(PathElement element, ResourceTransformer resourceTransformer, OperationTransformer operationTransformer) {
            return registerSubResource(element, PathAddressTransformer.DEFAULT, resourceTransformer, operationTransformer);
        }

        @Override
        public TransformersSubRegistration registerSubResource(PathElement element, PathAddressTransformer pathAddressTransformer, ResourceTransformer resourceTransformer, OperationTransformer operationTransformer) {
            return registerSubResource(element, pathAddressTransformer, resourceTransformer, operationTransformer, false, false);
        }

        @Override
        public TransformersSubRegistration registerSubResource(PathElement element, PathAddressTransformer pathAddressTransformer, ResourceTransformer resourceTransformer, OperationTransformer operationTransformer, boolean inherited, boolean placeholder) {
            final PathAddress address = current.append(element);
            for(final ModelVersion version : range.getVersions()) {
                registry.createChildRegistry(address, version, pathAddressTransformer, resourceTransformer, operationTransformer, inherited, placeholder);
            }
            return new TransformersSubRegistrationImpl(range, registry, address);
        }

        @Override
        public void discardOperations(String... operationNames) {
            for(final ModelVersion version : range.getVersions()) {
                for(final String operationName : operationNames) {
                    registry.discardOperation(current, version, operationName);
                }
            }
        }

        @Override
        public void registerOperationTransformer(String operationName, OperationTransformer transformer) {
            for(final ModelVersion version : range.getVersions()) {
                registry.registerTransformer(current, version, operationName, transformer);
            }
        }
    }

}
