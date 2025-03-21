package com.keycloak.test.service;

import org.springframework.stereotype.Service;

/**
 * Non-persistent KeycloakService implementation.
 * This version doesn't interact with any database.
 */
@Service
public class KeycloakService {
    // No database operations - this is just a placeholder service
    // All authentication is handled directly through Keycloak's APIs
}
