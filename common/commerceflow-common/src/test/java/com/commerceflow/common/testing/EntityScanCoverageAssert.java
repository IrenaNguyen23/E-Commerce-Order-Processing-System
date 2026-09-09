package com.commerceflow.common.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import jakarta.persistence.Entity;

/**
 * Checks that a service's {@code @EntityScan} actually covers its entities.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code @EntityScan} <b>replaces</b> Spring Boot's default package scanning rather than adding
 * to it. The moment a service declares one, every entity outside the listed packages becomes
 * invisible — and the failure is at startup, with "Not a managed type", not at compile time.
 *
 * <p>This platform had exactly that defect: Order Service listed one package and had entities in
 * six. It compiled, and 356 unit tests passed, because every one of them builds its collaborators
 * with Mockito and never starts a Spring context. The service would not have started.
 *
 * <h2>Why it is a plain unit test and not an integration test</h2>
 *
 * <p>The Testcontainers suites would have caught it, and they need Docker. This needs nothing: it
 * scans the classpath for {@code @Entity} and compares against the annotation. A guard that runs
 * on every developer's machine and in every build beats a better guard that runs somewhere else.
 *
 * <p>It does not replace the integration tests — it cannot tell whether Flyway matches the mapping
 * — but it closes the specific hole that let a service reach "all green" while being unable to
 * boot.
 */
public final class EntityScanCoverageAssert {

    private EntityScanCoverageAssert() {
    }

    /**
     * Asserts that every {@code @Entity} under {@code rootPackage} sits in a package the given
     * configuration scans, and that every repository package is likewise covered.
     *
     * @param jpaConfig the class carrying {@code @EntityScan} and {@code @EnableJpaRepositories}
     * @param rootPackage the service's own root, e.g. {@code com.commerceflow.orderservice}
     */
    public static void assertCovers(Class<?> jpaConfig, String rootPackage) {
        EntityScan entityScan = jpaConfig.getAnnotation(EntityScan.class);
        assertThat(entityScan)
                .as("%s must declare @EntityScan; without one the defaults apply and this check "
                        + "is meaningless", jpaConfig.getSimpleName())
                .isNotNull();

        Set<String> scanned = new LinkedHashSet<>(Arrays.asList(entityScan.basePackages()));
        Set<String> entityPackages = new TreeSet<>(packagesContainingEntities(rootPackage));

        List<String> missed = entityPackages.stream()
                .filter(pkg -> scanned.stream().noneMatch(pkg::startsWith))
                .toList();

        assertThat(missed)
                .as("@EntityScan replaces the default packages rather than adding to them, so an "
                        + "entity outside the listed packages is invisible and the service fails "
                        + "to start with \"Not a managed type\". Add these to %s: %s",
                        jpaConfig.getSimpleName(), missed)
                .isEmpty();
    }

    /** Asserts the repository scan covers the same ground, which fails the same way. */
    public static void assertRepositoriesCovered(Class<?> jpaConfig, String... repositoryPackages) {
        EnableJpaRepositories repositories = jpaConfig.getAnnotation(EnableJpaRepositories.class);
        assertThat(repositories)
                .as("%s must declare @EnableJpaRepositories", jpaConfig.getSimpleName())
                .isNotNull();

        Set<String> scanned = new LinkedHashSet<>(Arrays.asList(repositories.basePackages()));

        List<String> missed = Arrays.stream(repositoryPackages)
                .filter(pkg -> scanned.stream().noneMatch(pkg::startsWith))
                .toList();

        assertThat(missed)
                .as("A repository outside the scanned packages is simply not created, and the "
                        + "bean that needs it fails to autowire. Add these to %s: %s",
                        jpaConfig.getSimpleName(), missed)
                .isEmpty();
    }

    private static Set<String> packagesContainingEntities(String rootPackage) {
        // useDefaultFilters(false): only the annotation filter below applies, so this finds
        // entities and nothing else.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        return scanner.findCandidateComponents(rootPackage).stream()
                .map(definition -> definition.getBeanClassName())
                .filter(name -> name != null && name.contains("."))
                .map(name -> name.substring(0, name.lastIndexOf('.')))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
