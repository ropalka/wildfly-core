/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.model.test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.jboss.modules.maven.ArtifactCoordinates;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * @author Tomaz Cerar (c) 2015 Red Hat Inc.
 */
public class MavenSettingsTest {

    void clearCachedSettings() throws Exception {
        Field mavenSettings = MavenSettings.class.getDeclaredField("mavenSettings");
        mavenSettings.setAccessible(true);
        mavenSettings.set(null, null);
    }
    private static String passedLocalRepository;

    @BeforeClass
    public static void before(){
        passedLocalRepository = System.getProperty("localRepository");
        System.clearProperty("localRepository");
    }
    @AfterClass
    public static void after(){
        System.setProperty("localRepository", passedLocalRepository);
    }

    @Rule
    public TemporaryFolder tmpdir = new TemporaryFolder();

    @Test
    public void testWithPassedRepository() throws Exception {
        System.setProperty("maven.repo.local", tmpdir.newFolder("repository").getAbsolutePath());
        System.setProperty("remote.maven.repo", "https://repository.jboss.org/nexus/content/groups/public/,https://maven-central.storage.googleapis.com/");

        try {
            clearCachedSettings();
            MavenSettings settings = MavenSettings.getSettings();
            List<String> remoteRepos = settings.getRemoteRepositories().stream().map(MavenSettings.Repository::getUrl).toList();
            Assert.assertTrue(remoteRepos.size() >= 3); //at least 3 must be present, other can come from settings.xml
            Assert.assertTrue(remoteRepos.contains("https://repo1.maven.org/maven2/"));
            Assert.assertTrue(remoteRepos.contains("https://repository.jboss.org/nexus/content/groups/public/"));
            Assert.assertTrue(remoteRepos.contains("https://maven-central.storage.googleapis.com/"));

        } finally {
            System.clearProperty("maven.repo.local");
            System.clearProperty("remote.repository");
        }
    }

    @Test
    public void testWithEmptyPassedRepository() throws Exception {
        Path userRepo = tmpdir.newFolder(".m2", "repository").toPath();
        String userHome = System.getProperty("user.home");
        System.setProperty("user.home", tmpdir.getRoot().getAbsolutePath());
        System.setProperty("maven.repo.local", "");

        try {
            clearCachedSettings();
            MavenSettings settings = MavenSettings.getSettings();
            Assert.assertEquals(userRepo, settings.getLocalRepository());
        } finally {
            System.setProperty("user.home", userHome);
            System.clearProperty("maven.repo.local");
        }
    }

    @Test
    public void testEmptyLocalRepo() throws Exception {
        MavenSettings settings = new MavenSettings();

        MavenSettings.parseSettingsXml(Paths.get(MavenSettingsTest.class.getResource("settings-empty-local-repo.xml").toURI()), settings);
        Assert.assertNull(settings.getLocalRepository());//local repo shouldn't be set

    }

    @Test
    public void testServersConfig() throws Exception {
        MavenSettings settings = new MavenSettings();
        MavenSettings.parseSettingsXml(Paths.get(MavenSettingsTest.class.getResource("settings-with-auth.xml").toURI()), settings);
        settings.resolveActiveSettings();

        List<Pair<String, String>> remoteRepositories = settings.getRemoteRepositories().stream()
                .map(r -> Pair.of(r.getId(), r.getUrl()))
                .toList();
        Assert.assertTrue(remoteRepositories.contains(Pair.of("central", "https://repo1.maven.org/maven2/")));
        Assert.assertTrue(remoteRepositories.contains(Pair.of("custom-repo", "https:/example.org/custom-repo/")));
        Assert.assertTrue(remoteRepositories.contains(Pair.of("another-repo", "https:/example.org/another-repo/")));
        Assert.assertTrue(remoteRepositories.contains(Pair.of("non-authenticated-repo", "https:/example.org/non-authenticated-repo/")));

        List<Triple<String, String, String>> servers = settings.getServers().values().stream()
                .map(s -> Triple.of(s.getId(), s.getUsername(), s.getPassword()))
                .toList();
        Assert.assertTrue(servers.contains(Triple.of("custom-repo", "custom-user", "123")));
        Assert.assertTrue(servers.contains(Triple.of("another-repo", null, "abcd")));
        Assert.assertFalse(settings.getServers().containsKey("non-authenticated-repo"));
    }

    @Test
    public void testActivationTypes() throws Exception {
        MavenSettings settings = new MavenSettings();
        MavenSettings.parseSettingsXml(Paths.get(MavenSettingsTest.class.getResource("settings-with-various-activations.xml").toURI()), settings);
        settings.resolveActiveSettings();

        List<String> repoIds = settings.getRemoteRepositories().stream()
                .map(MavenSettings.Repository::getId)
                .toList();

        // Only profiles with activeByDefault=true should be activated
        Assert.assertTrue("active-repo should be present (activeByDefault=true)", repoIds.contains("active-repo"));
        Assert.assertTrue("mixed-repo should be present (activeByDefault=true)", repoIds.contains("mixed-repo"));

        // Profiles with other activation types should NOT be activated
        Assert.assertFalse("property-repo should not be present (property activation)", repoIds.contains("property-repo"));
        Assert.assertFalse("jdk-repo should not be present (jdk activation)", repoIds.contains("jdk-repo"));
        Assert.assertFalse("os-repo should not be present (os activation)", repoIds.contains("os-repo"));
        Assert.assertFalse("file-repo should not be present (file activation)", repoIds.contains("file-repo"));
        Assert.assertFalse("not-active-repo should not be present (activeByDefault=false)", repoIds.contains("not-active-repo"));
        Assert.assertFalse("property-name-repo should not be present (property name only)", repoIds.contains("property-name-repo"));
        Assert.assertFalse("file-missing-repo should not be present (file missing activation)", repoIds.contains("file-missing-repo"));

        // central should always be present (default)
        Assert.assertTrue("central should always be present", repoIds.contains("central"));
    }

    /**
     * testing is snapshot resolving works properly, as in case of snapshot version, we need to use different path than exact version.
     * @throws Exception
     */
    @Test
    public void testSnapshotResolving()throws Exception{
        ArtifactCoordinates coordinates = ArtifactCoordinates.fromString("org.wildfly.core:wildfly-version:2.0.5.Final-20151222.144931-1");
        String path = coordinates.relativeArtifactPath('/');
        Assert.assertEquals("org/wildfly/core/wildfly-version/2.0.5.Final-SNAPSHOT/wildfly-version-2.0.5.Final-20151222.144931-1", path);
    }
}
