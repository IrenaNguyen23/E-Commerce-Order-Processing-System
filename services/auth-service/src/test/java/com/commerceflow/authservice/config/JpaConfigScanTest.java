package com.commerceflow.authservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerceflow.common.testing.EntityScanCoverageAssert;

/**
 * The service can actually see its own entities.
 *
 * <p>{@code @EntityScan} <b>replaces</b> Spring Boot's default package scanning rather than
 * adding to it, so an entity in a package nobody listed is invisible and the service fails to
 * start with "Not a managed type".
 *
 * <p>This platform had exactly that: Order Service listed one package and had entities in six. It
 * compiled, and every unit test passed, because those tests build their collaborators with Mockito
 * and never start a Spring context. The Testcontainers suites would have caught it and they need
 * Docker, which this machine does not have — so the guard is here instead, where it costs nothing
 * and runs on every build.
 */
class JpaConfigScanTest {

    @Test
    @DisplayName("every @Entity in this service sits in a package @EntityScan covers")
    void entityScanCoversEveryEntity() {
        EntityScanCoverageAssert.assertCovers(JpaConfig.class, "com.commerceflow.authservice");
    }

    @Test
    @DisplayName("every repository package is scanned")
    void repositoryScanCoversEveryRepository() {
        EntityScanCoverageAssert.assertRepositoriesCovered(JpaConfig.class,
                "com.commerceflow.authservice.repository");
    }
}
