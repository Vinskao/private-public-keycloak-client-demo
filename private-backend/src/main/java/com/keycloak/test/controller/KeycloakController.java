package com.keycloak.test.controller;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/keycloak")
public class KeycloakController {

    private static final Logger log = LoggerFactory.getLogger(KeycloakController.class);

    @Autowired
    private RestTemplate restTemplate;

    private String clientId = "test-private";
    private String clientSecret = "axMsoGFAXmMXOfx7CnHn776nyZl2hzsX";
    private String realm = "PeopleSystem";

    @GetMapping("/redirect")
    public void keycloakRedirect(@RequestParam("code") String code, HttpServletResponse response)
            throws IOException {
        String redirectUri = "http://localhost:8080/keycloak/redirect";
        String tokenUrl = "https://peoplesystem.tatdvsonorth.com/sso/realms/PeopleSystem/protocol/openid-connect/token";

        try {
            log.info("Received authorization code: {}", code);

            // Token Request
            MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
            tokenParams.add("client_id", clientId);
            tokenParams.add("client_secret", clientSecret);
            tokenParams.add("code", code);
            tokenParams.add("grant_type", "authorization_code");
            tokenParams.add("redirect_uri", redirectUri);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/x-www-form-urlencoded");
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(tokenParams, headers);

            ResponseEntity<Map> tokenResponse = restTemplate.exchange(tokenUrl, HttpMethod.POST, entity, Map.class);
            String accessToken = (String) tokenResponse.getBody().get("access_token");
            String refreshToken = (String) tokenResponse.getBody().get("refresh_token");

            log.info("Access Token: {}", accessToken);
            log.info("Refresh Token: {}", refreshToken);

            if (accessToken == null || refreshToken == null) {
                throw new RuntimeException("Failed to obtain access token");
            }

            // Request User Info
            String userInfoUrl = "https://peoplesystem.tatdvsonorth.com/sso/realms/PeopleSystem/protocol/openid-connect/userinfo";
            HttpHeaders userHeaders = new HttpHeaders();
            userHeaders.set("Authorization", "Bearer " + accessToken);
            HttpEntity<String> userEntity = new HttpEntity<>(userHeaders);

            ResponseEntity<Map> userResponse = restTemplate.exchange(userInfoUrl, HttpMethod.GET, userEntity,
                    Map.class);
            Map<String, Object> userInfo = userResponse.getBody();

            log.info("User Info: {}", userInfo);

            String preferredUsername = (String) userInfo.get("preferred_username");
            if (preferredUsername == null) {
                throw new RuntimeException("Failed to retrieve user info");
            }

            // 獲取用戶信息後立即使session過期，註銷refresh token
            invalidateSession(refreshToken);

            // Set cookies for client - 使用極短的過期時間（即使設置也會很快過期）
            response.setHeader("Set-Cookie", "refreshToken=; Path=/; Max-Age=0; SameSite=Lax");
            response.addHeader("Set-Cookie", "authorizationCode=" + code + "; Path=/; Max-Age=60; SameSite=Lax"); // 1分鐘
            response.addHeader("Set-Cookie", "refreshToken=" + refreshToken + "; Path=/; Max-Age=60; SameSite=Lax"); // 1分鐘
            response.addHeader("Set-Cookie", "accessToken=" + accessToken + "; Path=/; Max-Age=60; SameSite=Lax"); // 1分鐘

            // Get user email from userInfo
            String email = (String) userInfo.get("email");
            
            // Redirect to client with user info
            response.sendRedirect(
                    "http://127.0.0.1:5500/private-frontend/login.html" + "?username=" + preferredUsername + "&email=" + email
                            + "&token=" + accessToken);
        } catch (Exception e) {
            log.error("Error processing OAuth redirect", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error processing OAuth redirect");
        }
    }

    /**
     * 立即使session過期的方法
     */
    private void invalidateSession(String refreshToken) {
        try {
            String logoutUrl = "https://peoplesystem.tatdvsonorth.com/sso/realms/PeopleSystem/protocol/openid-connect/logout";
            
            // 在使用令牌後立即後台註銷refresh token
            // 但不影響當前用戶的訪問（access token仍然有效一小段時間）
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("refresh_token", refreshToken);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/x-www-form-urlencoded");
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
            
            // 非同步執行，不等待響應
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // 等待1秒，確保用戶信息已經獲取並處理完畢
                    restTemplate.exchange(logoutUrl, HttpMethod.POST, entity, String.class);
                    log.info("Session invalidated for refresh token");
                } catch (Exception e) {
                    log.error("Failed to invalidate session", e);
                }
            }).start();
        } catch (Exception e) {
            log.error("Error invalidating session", e);
        }
    }

    @CrossOrigin
    @GetMapping("/logout")
    public ResponseEntity<?> logout(@RequestParam("refreshToken") String refreshToken) {
        String logoutUrl = "https://peoplesystem.tatdvsonorth.com/sso/realms/PeopleSystem/protocol/openid-connect/logout";

        try {
            // Revoke Token
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/x-www-form-urlencoded");

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("refresh_token", refreshToken);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);

            restTemplate.exchange(logoutUrl, HttpMethod.POST, entity, String.class);

            return ResponseEntity.ok("Logout successful");
        } catch (Exception e) {
            log.error("Logout failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Logout failed");
        }
    }

    @CrossOrigin
    @GetMapping("/introspect")
    public ResponseEntity<?> introspectToken(@RequestParam("token") String token) {
        String introspectUrl = "https://peoplesystem.tatdvsonorth.com/sso/realms/PeopleSystem/protocol/openid-connect/token/introspect";

        try {
            MultiValueMap<String, String> bodyParams = new LinkedMultiValueMap<>();
            bodyParams.add("client_id", clientId);
            bodyParams.add("client_secret", clientSecret);
            bodyParams.add("token", token);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/x-www-form-urlencoded");
            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(bodyParams, headers);

            ResponseEntity<Map> introspectResponse = restTemplate.exchange(introspectUrl, HttpMethod.POST, entity,
                    Map.class);
            Map<String, Object> introspectionResult = introspectResponse.getBody();

            if (introspectionResult != null && Boolean.TRUE.equals(introspectionResult.get("active"))) {
                return ResponseEntity.ok(introspectionResult);
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Token is not active or invalid.");
            }
        } catch (Exception e) {
            log.error("Error introspecting token", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error introspecting token.");
        }
    }

    @CrossOrigin
    @PostMapping("/createUser")
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> userDetails,
            @RequestParam("accessToken") String accessToken) {

        try {
            String url = "https://peoplesystem.tatdvsonorth.com/sso/admin/realms/" + realm + "/users";
            Map<String, Object> userRepresentation = new HashMap<>();
            userRepresentation.put("username", userDetails.get("username"));
            userRepresentation.put("email", userDetails.get("email"));
            userRepresentation.put("firstName", userDetails.get("firstName"));
            userRepresentation.put("lastName", userDetails.get("lastName"));
            userRepresentation.put("enabled", true);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(userRepresentation, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return ResponseEntity.ok("User created successfully.");
            } else {
                return ResponseEntity.status(response.getStatusCode()).body("Failed to create user.");
            }

        } catch (Exception e) {
            log.error("Error creating user", e);
            return ResponseEntity.status(500).body("Error creating user.");
        }
    }

    // @GetMapping("/getUserInfo")
    // public ResponseEntity<?> getUserInfo(@RequestParam("authorizationCode")
    // String authorizationCode) {
    // String tokenUrl =
    // "https://peoplesystem.tatdvsonorth.com/sso/realms/PeopleSystem/protocol/openid-connect/token";

    // try {
    // MultiValueMap<String, String> bodyParams = new LinkedMultiValueMap<>();
    // bodyParams.add("client_id", clientId);
    // bodyParams.add("client_secret", clientSecret);
    // bodyParams.add("grant_type", "authorization_code");
    // bodyParams.add("code", authorizationCode);
    // bodyParams.add("redirect_uri",
    // "http://localhost:8080/keycloak/redirect");

    // HttpHeaders headers = new HttpHeaders();
    // headers.set("Content-Type", "application/x-www-form-urlencoded");
    // HttpEntity<MultiValueMap<String, String>> entity = new
    // HttpEntity<>(bodyParams, headers);

    // ResponseEntity<Map> tokenResponse = restTemplate.exchange(tokenUrl,
    // HttpMethod.POST, entity, Map.class);
    // String accessToken = (String) tokenResponse.getBody().get("access_token");

    // if (accessToken == null) {
    // throw new RuntimeException("Failed to obtain access token");
    // }

    // String userInfoUrl =
    // "https://peoplesystem.tatdvsonorth.com/sso/realms/PeopleSystem/protocol/openid-connect/userinfo";
    // HttpHeaders userHeaders = new HttpHeaders();
    // userHeaders.set("Authorization", "Bearer " + accessToken);
    // HttpEntity<String> userEntity = new HttpEntity<>(userHeaders);

    // ResponseEntity<Map> userResponse = restTemplate.exchange(userInfoUrl,
    // HttpMethod.GET, userEntity,
    // Map.class);
    // Map<String, Object> userInfo = userResponse.getBody();

    // if (userInfo != null) {
    // return ResponseEntity.ok(userInfo);
    // } else {
    // return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User info not
    // found.");
    // }
    // } catch (Exception e) {
    // log.error("Error retrieving user info", e);
    // return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error
    // retrieving user info.");
    // }
    // }
}
