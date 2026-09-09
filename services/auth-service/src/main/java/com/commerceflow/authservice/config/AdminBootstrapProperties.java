package com.commerceflow.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Bootstrap back-office account, disabled by default. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.bootstrap.admin")
public class AdminBootstrapProperties {

    /** Creates the account on start-up when true. */
    private boolean enabled;

    private String email = "admin@commerceflow.io";

    /** Supplied through an environment variable or a Kubernetes secret; never committed. */
    private String password;

    private String fullName = "CommerceFlow Administrator";
}
