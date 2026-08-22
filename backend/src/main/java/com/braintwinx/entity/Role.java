package com.braintwinx.entity;

/**
 * Application roles (project brief section 7).
 *
 * <p>Authorisation is deny-by-default: possessing a role grants nothing implicitly.
 * Each endpoint declares its own requirement explicitly.
 */
public enum Role {

    /** Full administrative access, including user and model-registry management. */
    ADMIN,

    /** Clinical user: manages patients, uploads scans, runs analyses, issues reports. */
    DOCTOR,

    /**
     * Research user. Intended for aggregate and methodological work rather than
     * individual care; scope is narrowed at the endpoint level, not assumed here.
     */
    RESEARCHER;

    /** @return the Spring Security authority name for this role. */
    public String authority() {
        return "ROLE_" + name();
    }
}
